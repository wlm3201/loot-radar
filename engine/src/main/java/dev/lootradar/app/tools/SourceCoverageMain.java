package dev.lootradar.app.tools;

import com.google.gson.GsonBuilder;
import dev.lootradar.core.structure.PlacementSpec;
import dev.lootradar.core.structure.RandomSpreadLocator;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * 结构源普查的「预测」一侧：按版本目录枚举结构起点、扫描容器并写出 {@code loot-radar-source-prediction/1}，与 dump-mod 的 {@code
 * loot-radar-source-truth/1} 配对后由覆盖率脚本统计。
 */
public final class SourceCoverageMain {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        int radius = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        String dimension = "minecraft:" + args[3];
        long began = System.nanoTime();
        var root = new LinkedHashMap<String, Object>();
        var rows = new ArrayList<Map<String, Object>>();
        var times = new TreeMap<String, Double>();
        root.put("schema", "loot-radar-source-prediction/1");
        root.put("seed", Long.toString(seed));
        root.put("dimension", dimension);
        root.put("radius", radius);
        root.put("center", List.of(0, 0));
        root.put("complete", false);
        root.put("coverageScope", "full_circle_sources_except_dungeon_and_mineshaft");
        root.put("containers", rows);
        root.put("scanSecondsByStructure", times);
        int perTypeCap = Integer.getInteger("lootradar.sourcePerTypeCap", 100);
        var foundByType = new TreeMap<String, Integer>();
        var usedByType = new TreeMap<String, Integer>();
        root.put("perTypeCap", perTypeCap);
        root.put("startsByType", foundByType);
        root.put("usedByType", usedByType);
        try (var runtime = VanillaRuntime.load(seed)) {
            root.put("version", SharedConstants.getCurrentVersion().id());
            var version = GameVersion.parse(SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var oracle = new VanillaLootOracle(runtime);
            root.put("initializationSeconds", (System.nanoTime() - began) / 1e9);
            for (var set : registry.sets().values()) {
                if (set.structures().stream()
                        .noneMatch(
                                m ->
                                        ("minecraft:" + registry.structure(m.id()).dimension())
                                                .equals(dimension))) continue;
                if (set.placement().type() == PlacementSpec.Type.FEATURE) continue;
                if (set.structures().stream().noneMatch(m -> wanted(m.id()))) continue;
                long scanBegan = System.nanoTime();
                List<ChunkPos> candidates = new ArrayList<>();
                if (set.placement().isRandomSpread()) {
                    for (var p :
                            RandomSpreadLocator.candidatesInRadius(
                                    set, seed, 0, 0, (radius + 527) / 16, registry.sets()))
                        candidates.add(new ChunkPos(p.chunkX(), p.chunkZ()));
                } else candidates.addAll(runtime.ringPositions(set.id(), dimension));
                candidates.sort(
                        Comparator.comparingLong(
                                c ->
                                        (long) c.getMinBlockX() * c.getMinBlockX()
                                                + (long) c.getMinBlockZ() * c.getMinBlockZ()));
                var members =
                        set.structures().stream()
                                .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                                .toList();
                for (var candidate : candidates) {
                    if (members.stream()
                            .allMatch(m -> usedByType.getOrDefault(m.id(), 0) >= perTypeCap)) break;
                    long x = candidate.getMinBlockX() + 8L, z = candidate.getMinBlockZ() + 8L;
                    if (x * x + z * z > (long) (radius + 512) * (radius + 512)) continue;
                    var start = runtime.generateSelectedStructure(members, dimension, candidate);
                    if (!VanillaRuntime.isValidStart(start)) continue;
                    var box = start.getBoundingBox();
                    long nearX = Math.clamp(0, box.minX(), box.maxX()),
                            nearZ = Math.clamp(0, box.minZ(), box.maxZ());
                    if (nearX * nearX + nearZ * nearZ > (long) radius * radius) continue;
                    String id =
                            runtime.registries()
                                    .lookupOrThrow(Registries.STRUCTURE)
                                    .getKey(start.getStructure())
                                    .toString();
                    foundByType.merge(id, 1, Integer::sum);
                    int used = usedByType.getOrDefault(id, 0);
                    if (used >= perTypeCap) continue;
                    usedByType.put(id, used + 1);
                    for (var source : StructureScanner.scan(runtime, id, dimension, start))
                        add(rows, oracle, source, dimension, id, radius);
                }
                times.put(set.id(), (System.nanoTime() - scanBegan) / 1e9);
                write(output, root);
            }
            if (dimension.equals("minecraft:overworld")) {
                long scanBegan = System.nanoTime();
                int bound = (radius + 31) / 16;
                var wellAttempts = new java.util.ArrayList<ChunkPos>();
                for (int x = -bound; x <= bound; x++)
                    for (int z = -bound; z <= bound; z++) {
                        var p = new ChunkPos(x, z);
                        long bx = p.getMinBlockX() + 8L, bz = p.getMinBlockZ() + 8L;
                        if (bx * bx + bz * bz > (long) (radius + 32) * (radius + 32)) continue;
                        if (!runtime.biomeAt(
                                        dimension,
                                        new net.minecraft.core.BlockPos(
                                                p.getMinBlockX() + 8, 64, p.getMinBlockZ() + 8))
                                .is(net.minecraft.world.level.biome.Biomes.DESERT)) continue;
                        if (FeatureScanner.rarityAllows(
                                runtime,
                                "minecraft:desert_well",
                                dimension,
                                p,
                                registry.structures().get("minecraft:desert_well").rarity(),
                                registry)) wellAttempts.add(p);
                    }
                wellAttempts.sort(
                        Comparator.comparingLong(
                                c ->
                                        (long) c.getMinBlockX() * c.getMinBlockX()
                                                + (long) c.getMinBlockZ() * c.getMinBlockZ()));
                int wellsUsed = 0;
                for (var p : wellAttempts) {
                    if (wellsUsed >= perTypeCap) break;
                    wellsUsed++;
                    foundByType.merge("minecraft:desert_well", 1, Integer::sum);
                    for (var source :
                            FeatureScanner.scan(runtime, "minecraft:desert_well", dimension, p))
                        add(rows, oracle, source, dimension, "minecraft:desert_well", radius);
                }
                usedByType.put("minecraft:desert_well", wellsUsed);
                times.put("minecraft:desert_well", (System.nanoTime() - scanBegan) / 1e9);
            }
            root.put("complete", true);
        } catch (Throwable error) {
            root.put("error", error.toString());
            write(output, root);
            throw error;
        }
        root.put("elapsedSeconds", (System.nanoTime() - began) / 1e9);
        write(output, root);
    }

    private static boolean wanted(String id) {
        var focus = System.getProperty("lootradar.focusStructures");
        if (focus != null && !focus.isBlank()) {
            return java.util.Arrays.stream(focus.split(",")).map(String::trim).anyMatch(id::equals);
        }
        if (id.startsWith("minecraft:abandoned_camp")) return false;
        return !Set.of(
                        "minecraft:mineshaft",
                        "minecraft:mineshaft_mesa",
                        "minecraft:nether_fossil",
                        "minecraft:swamp_hut")
                .contains(id);
    }

    private static void add(
            List<Map<String, Object>> rows,
            VanillaLootOracle oracle,
            ContainerRecord source,
            String dimension,
            String structure,
            int radius) {
        long x = source.pos().getX(), z = source.pos().getZ();
        if (x * x + z * z > (long) radius * radius) return;
        var row = new LinkedHashMap<String, Object>();
        row.put("pos", List.of(source.pos().getX(), source.pos().getY(), source.pos().getZ()));
        row.put("structure", structure);
        row.put("table", source.lootTable());
        row.put("kind", source.kind().name().toLowerCase(Locale.ROOT));
        row.put("block", source.blockId());
        row.put("lootSeed", Long.toString(source.lootSeed()));
        var items = new TreeMap<String, Integer>();
        if (source.kind() == ContainerKind.POT_SHERD)
            source.potSherds().forEach(item -> items.merge(item, 1, Integer::sum));
        else if (source.hasLootTable() && source.lootSeed() != 0)
            for (var item :
                    oracle.roll(
                            source.lootTable(),
                            source.lootSeed(),
                            dimension,
                            Vec3.atCenterOf(source.pos())))
                items.merge(item.itemId(), item.count(), Integer::sum);
        row.put("items", items);
        rows.add(row);
    }

    private static void write(Path output, Map<String, Object> root) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
    }
}
