package dev.lootradar.app.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.ContainerKind;
import dev.lootradar.engine.ContainerRecord;
import dev.lootradar.engine.DungeonScanner;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** 刷怪房专项预测：逐区块重放 monster_room 特征，写出 simple_dungeon 容器清单。 */
public final class DungeonCoverageMain {
    private static final String TABLE = "minecraft:chests/simple_dungeon";

    public static void main(String[] args) throws Exception {
        Path truthPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        JsonObject truth = JsonParser.parseString(Files.readString(truthPath)).getAsJsonObject();
        long seed = Long.parseLong(truth.get("seed").getAsString());
        String dimension = truth.get("dimension").getAsString();
        int radius = truth.get("radius").getAsInt();

        boolean dungeonOnly = args.length > 2 && args[2].equals("truth-dungeon-chunks");
        TreeSet<Long> chunks = new TreeSet<>();
        if (!dungeonOnly) {
            for (var element : truth.getAsJsonArray("starts")) {
                JsonObject start = element.getAsJsonObject();
                JsonArray box = start.getAsJsonArray("box");
                for (int x = box.get(0).getAsInt() >> 4; x <= box.get(3).getAsInt() >> 4; x++)
                    for (int z = box.get(2).getAsInt() >> 4; z <= box.get(5).getAsInt() >> 4; z++)
                        chunks.add(pack(x, z));
            }
        }
        for (var element : truth.getAsJsonArray("containers")) {
            JsonObject row = element.getAsJsonObject();
            if (dungeonOnly && !(row.has("table") && TABLE.equals(row.get("table").getAsString())))
                continue;
            JsonArray pos = row.getAsJsonArray("pos");
            chunks.add(pack(pos.get(0).getAsInt() >> 4, pos.get(2).getAsInt() >> 4));
        }

        long began = System.nanoTime();
        var root = new LinkedHashMap<String, Object>();
        var rows = new ArrayList<Map<String, Object>>();
        root.put("schema", "loot-radar-dungeon-prediction/1");
        root.put("seed", Long.toString(seed));
        root.put("dimension", dimension);
        root.put("radius", radius);
        root.put("chunksScanned", chunks.size());
        root.put("containers", rows);
        try (var runtime = VanillaRuntime.load(seed)) {
            root.put("version", net.minecraft.SharedConstants.getCurrentVersion().id());
            var version = GameVersion.parse(net.minecraft.SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var oracle = new VanillaLootOracle(runtime);
            long scanned = System.nanoTime();
            for (long key : chunks) {
                var chunk = new ChunkPos((int) (key >> 32), (int) (long) key);
                for (ContainerRecord source :
                        DungeonScanner.scan(runtime, dimension, chunk, registry)) {
                    if (!source.lootTable().equals(TABLE)) continue;
                    long x = source.pos().getX(), z = source.pos().getZ();
                    if (x * x + z * z > (long) radius * radius) continue;
                    add(rows, oracle, source, dimension);
                }
            }
            root.put("scanSeconds", (System.nanoTime() - scanned) / 1e9);
        }
        root.put("elapsedSeconds", (System.nanoTime() - began) / 1e9);
        root.put("complete", true);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static void add(
            List<Map<String, Object>> rows,
            VanillaLootOracle oracle,
            ContainerRecord source,
            String dimension) {
        var row = new LinkedHashMap<String, Object>();
        row.put("pos", List.of(source.pos().getX(), source.pos().getY(), source.pos().getZ()));
        row.put("structure", "minecraft:monster_room");
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
}
