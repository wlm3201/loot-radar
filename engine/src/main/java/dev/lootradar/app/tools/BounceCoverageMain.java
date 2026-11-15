package dev.lootradar.app.tools;

import com.google.gson.GsonBuilder;
import dev.lootradar.core.structure.RandomSpreadLocator;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.*;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Bounce 唱片专项预测：穷举指定半径内的矿井起点，找出硫黄洞穴走廊里的箱子矿车， 写出与 {@code BounceCoverageDump} 真值同构的 prediction.json。
 */
public final class BounceCoverageMain {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String DISC = "minecraft:music_disc_bounce";
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("outputDir radius seed...");
        Path output = Path.of(args[0]);
        int radius = Integer.parseInt(args[1]);
        for (int i = 2; i < args.length; i++) run(output, radius, Long.parseLong(args[i]));
    }

    private static void run(Path output, int radius, long seed) throws Exception {
        long began = System.nanoTime();
        try (var runtime = VanillaRuntime.load(seed)) {
            String version = SharedConstants.getCurrentVersion().id();
            Path path = output.resolve(version + "_" + seed).resolve("prediction.json");
            var registry =
                    VersionRegistry.load(
                            GameVersion.parse(version), VersionRegistry.resolveDataRoot());
            var set = registry.sets().get("minecraft:mineshafts");
            var members =
                    set.structures().stream()
                            .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                            .toList();
            var candidates =
                    RandomSpreadLocator.candidatesInRadius(
                            set, seed, 0, 0, (radius + 192 + 15) / 16, registry.sets());
            var oracle = new VanillaLootOracle(runtime);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", "loot-radar-bounce-prediction/1");
            report.put("version", version);
            report.put("seed", Long.toString(seed));
            report.put("center", List.of(0, 0));
            report.put("radius", radius);
            report.put("complete", false);
            report.put("candidateStarts", candidates.size());
            report.put("timeBudget", "unlimited");
            report.put("terrain", "native_noise_surface_carvers");
            List<Map<String, Object>> carts = new ArrayList<>();
            report.put("predictedCarts", carts);
            int checked = 0, valid = 0, scanned = 0;
            try (var workers = Executors.newFixedThreadPool(4)) {
                var completion = new ExecutorCompletionService<Result>(workers);
                for (var candidate : candidates)
                    completion.submit(
                            () -> {
                                ChunkPos chunk =
                                        new ChunkPos(candidate.chunkX(), candidate.chunkZ());
                                var start =
                                        runtime.generateSelectedStructure(
                                                members, DIMENSION, chunk);
                                if (!VanillaRuntime.isValidStart(start))
                                    return new Result(false, false, List.of());
                                if (!MineshaftBiomes.mayContainBounce(runtime, DIMENSION, start))
                                    return new Result(true, false, List.of());
                                String structure =
                                        runtime.registries()
                                                .lookupOrThrow(Registries.STRUCTURE)
                                                .getKey(start.getStructure())
                                                .toString();
                                List<Map<String, Object>> rows = new ArrayList<>();
                                for (var cart :
                                        StructureScanner.scan(
                                                runtime,
                                                structure,
                                                DIMENSION,
                                                start,
                                                MineshaftBiomes.bounceChunks(
                                                        runtime, DIMENSION, start))) {
                                    if (!cart.lootTable()
                                            .equals("minecraft:chests/abandoned_mineshaft"))
                                        continue;
                                    long x = cart.pos().getX(), z = cart.pos().getZ();
                                    if (x * x + z * z > (long) radius * radius) continue;
                                    int count =
                                            oracle
                                                    .roll(
                                                            cart.lootTable(),
                                                            cart.lootSeed(),
                                                            DIMENSION,
                                                            Vec3.atCenterOf(cart.pos()))
                                                    .stream()
                                                    .filter(item -> item.itemId().equals(DISC))
                                                    .mapToInt(LootStack::count)
                                                    .sum();
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put(
                                            "pos",
                                            List.of(
                                                    cart.pos().getX(),
                                                    cart.pos().getY(),
                                                    cart.pos().getZ()));
                                    row.put("lootSeed", Long.toString(cart.lootSeed()));
                                    row.put("bounceCount", count);
                                    row.put("structure", structure);
                                    row.put("startChunk", List.of(chunk.x(), chunk.z()));
                                    long rootX = chunk.getMinBlockX() + 8L,
                                            rootZ = chunk.getMinBlockZ() + 8L;
                                    row.put(
                                            "withinSearchRootRadius",
                                            rootX * rootX + rootZ * rootZ
                                                    <= (long) radius * radius);
                                    rows.add(row);
                                }
                                return new Result(true, true, rows);
                            });
                while (checked < candidates.size()) {
                    Result result = completion.take().get();
                    checked++;
                    if (result.valid()) valid++;
                    if (result.scanned()) scanned++;
                    carts.addAll(result.carts());
                    if (checked % 100 == 0 || checked == candidates.size()) {
                        report.put("startsChecked", checked);
                        report.put("validStarts", valid);
                        report.put("scannedStarts", scanned);
                        report.put("elapsedSeconds", (System.nanoTime() - began) / 1e9);
                        write(path, report);
                        OUT.printf(
                                "PREDICTION %s seed=%d starts=%d/%d scanned=%d bounce=%d%n",
                                version,
                                seed,
                                checked,
                                candidates.size(),
                                scanned,
                                carts.stream()
                                        .filter(c -> ((Number) c.get("bounceCount")).intValue() > 0)
                                        .count());
                    }
                }
            } catch (Throwable error) {
                report.put("error", error.toString());
                write(path, report);
                throw error;
            }
            report.put("complete", true);
            report.put("elapsedSeconds", (System.nanoTime() - began) / 1e9);
            write(path, report);
        }
    }

    private record Result(boolean valid, boolean scanned, List<Map<String, Object>> carts) {}

    private static void write(Path path, Map<String, Object> report) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
