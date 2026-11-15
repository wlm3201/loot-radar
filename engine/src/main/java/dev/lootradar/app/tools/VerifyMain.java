package dev.lootradar.app.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.ContainerRecord;
import dev.lootradar.engine.FeatureScanner;
import dev.lootradar.engine.LootStack;
import dev.lootradar.engine.StructureScanner;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * 把 dump-mod 产出的 {@code loot-radar-dump/2} 容器真值与引擎预测逐位置对比， 按获取方式/战利品表统计覆盖率、种子一致率与内容一致率，输出 {@code
 * docs/verify-report.md}。用法：{@code runVerify -Pdump=<containers.json>}。
 */
public final class VerifyMain {

    private static final Gson GSON = new Gson();

    private static final class Bucket {
        int truth;
        int predicted;
        int matched;
        int contentMatched;
        long nanos;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                    "usage: VerifyMain <containers-dump.json>   (dump-mod run output)");
        }
        Path dumpPath = Path.of(args[0]);
        JsonObject dump =
                GSON.fromJson(Files.readString(dumpPath, StandardCharsets.UTF_8), JsonObject.class);
        long seed = dump.get("seed").getAsLong();
        int radius = dump.get("radius").getAsInt();
        int centerX = dump.getAsJsonArray("center").get(0).getAsInt();
        int centerZ = dump.getAsJsonArray("center").get(2).getAsInt();

        Map<String, JsonObject> truth = new HashMap<>();
        for (JsonElement element : dump.getAsJsonArray("containers")) {
            JsonObject row = element.getAsJsonObject();
            truth.put(posKey(row.getAsJsonArray("pos")), row);
        }

        VersionRegistry versions =
                VersionRegistry.load(
                        GameVersion.parse(dump.get("version").getAsString()),
                        VersionRegistry.resolveDataRoot());
        Map<String, JsonObject> predicted = new HashMap<>();
        Map<String, Long> scanNanosPerStructure = new TreeMap<>();
        long totalStartedAt = System.nanoTime();
        VanillaLootOracle oracle;
        try (VanillaRuntime runtime = VanillaRuntime.load(seed)) {
            oracle = new VanillaLootOracle(runtime);
            int centerChunkX = Math.floorDiv(centerX, 16);
            int centerChunkZ = Math.floorDiv(centerZ, 16);
            int radiusChunks = Math.floorDiv(radius, 16) + 1;
            for (var entry : versions.sets().entrySet()) {
                if (entry.getValue().placement().type()
                        == dev.lootradar.core.structure.PlacementSpec.Type.FEATURE) {
                    continue;
                }
                for (var member : entry.getValue().structures()) {
                    var spec = versions.structure(member.id());
                    List<ChunkPos> candidates = new ArrayList<>();
                    if (entry.getValue().placement().isRandomSpread()) {
                        for (var candidate :
                                dev.lootradar.core.structure.RandomSpreadLocator.candidatesInRadius(
                                        entry.getValue(),
                                        seed,
                                        centerChunkX,
                                        centerChunkZ,
                                        radiusChunks,
                                        versions.sets())) {
                            candidates.add(new ChunkPos(candidate.chunkX(), candidate.chunkZ()));
                        }
                    } else {
                        candidates.addAll(
                                runtime.ringPositions(entry.getValue().id(), spec.dimension()));
                    }
                    for (ChunkPos chunk : candidates) {
                        double dx = chunk.getMinBlockX() + 8 - centerX;
                        double dz = chunk.getMinBlockZ() + 8 - centerZ;
                        if (dx * dx + dz * dz > (double) radius * radius) {
                            continue;
                        }
                        long startedAt = System.nanoTime();
                        StructureStart start =
                                runtime.generateStructure(spec.id(), spec.dimension(), chunk);
                        if (VanillaRuntime.isValidStart(start)) {
                            for (ContainerRecord record :
                                    StructureScanner.scan(
                                            runtime, spec.id(), spec.dimension(), start)) {
                                predicted.put(
                                        posKey(record), toJson(oracle, record, spec.dimension()));
                            }
                        }
                        scanNanosPerStructure.merge(
                                spec.id(), System.nanoTime() - startedAt, Long::sum);
                    }
                }
            }
            collectFeatures(
                    runtime,
                    versions,
                    seed,
                    centerX,
                    centerZ,
                    radius,
                    predicted,
                    scanNanosPerStructure,
                    oracle);
        }
        long totalNanos = System.nanoTime() - totalStartedAt;

        Map<String, Bucket> perKind = new TreeMap<>();
        Map<String, Bucket> perTable = new TreeMap<>();
        List<String> missing = new ArrayList<>();
        List<String> contentMismatch = new ArrayList<>();

        for (Map.Entry<String, JsonObject> entry : truth.entrySet()) {
            JsonObject row = entry.getValue();
            String kind = row.get("kind").getAsString();
            String table = row.has("table") ? row.get("table").getAsString() : "-";
            Bucket kindBucket = perKind.computeIfAbsent(kind, ignored -> new Bucket());
            Bucket tableBucket = perTable.computeIfAbsent(table, ignored -> new Bucket());
            kindBucket.truth++;
            tableBucket.truth++;
            JsonObject got = predicted.get(entry.getKey());
            if (got == null) {
                missing.add(entry.getKey() + " " + kind + " " + table);
                continue;
            }
            kindBucket.predicted++;
            tableBucket.predicted++;
            boolean sameSeed = sameLong(row, got, "seed") && sameString(row, got, "table");
            if (!sameSeed) {
                continue;
            }
            kindBucket.matched++;
            tableBucket.matched++;
            if (contentMatches(row, got)) {
                kindBucket.contentMatched++;
                tableBucket.contentMatched++;
            } else {
                contentMismatch.add(entry.getKey() + " " + table);
            }
        }
        for (Map.Entry<String, JsonObject> entry : predicted.entrySet()) {
            if (!truth.containsKey(entry.getKey())) {
                String kind = entry.getValue().get("kind").getAsString();
                perKind.computeIfAbsent(kind, ignored -> new Bucket()).predicted++;
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("# verify 报告（").append(dumpPath.getFileName()).append("）\n\n");
        report.append("- 种子 ").append(seed).append("，半径 ").append(radius).append(" 格\n");
        report.append("- 真值容器 ")
                .append(truth.size())
                .append("，引擎命中 ")
                .append(predicted.size())
                .append("，扫描总耗时 ")
                .append(millis(totalNanos))
                .append("\n\n");

        report.append("## 按获取方式\n\n");
        report.append(
                "| 方式 | 服务端实际 | 引擎扫出 | 覆盖率 | 表+种子一致 | 内容一致率 |\n| --- | --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, Bucket> entry : perKind.entrySet()) {
            Bucket b = entry.getValue();
            report.append("| ")
                    .append(entry.getKey())
                    .append(" | ")
                    .append(b.truth)
                    .append(" | ")
                    .append(b.predicted)
                    .append(" | ")
                    .append(percent(b.matched, b.truth))
                    .append(" | ")
                    .append(b.matched)
                    .append(" | ")
                    .append(percent(b.contentMatched, b.matched))
                    .append(" |\n");
        }

        report.append("\n## 按战利品表\n\n");
        report.append("| 战利品表 | 服务端实际 | 引擎扫出 | 覆盖率 | 内容一致率 |\n| --- | --- | --- | --- | --- |\n");
        for (Map.Entry<String, Bucket> entry : perTable.entrySet()) {
            Bucket b = entry.getValue();
            report.append("| `")
                    .append(entry.getKey())
                    .append("`")
                    .append(" | ")
                    .append(b.truth)
                    .append(" | ")
                    .append(b.predicted)
                    .append(" | ")
                    .append(percent(b.matched, b.truth))
                    .append(" | ")
                    .append(percent(b.contentMatched, b.matched))
                    .append(" |\n");
        }

        report.append("\n## 扫描耗时（按结构类型）\n\n| 结构 | 耗时 |\n| --- | --- |\n");
        for (Map.Entry<String, Long> entry : scanNanosPerStructure.entrySet()) {
            report.append("| `")
                    .append(entry.getKey())
                    .append("` | ")
                    .append(millis(entry.getValue()))
                    .append(" |\n");
        }

        if (!missing.isEmpty()) {
            report.append("\n## 引擎缺失（").append(missing.size()).append("）\n\n");
            missing.forEach(line -> report.append("- ").append(line).append('\n'));
        }
        if (!contentMismatch.isEmpty()) {
            report.append("\n## 内容不一致（").append(contentMismatch.size()).append("）\n\n");
            contentMismatch.forEach(line -> report.append("- ").append(line).append('\n'));
        }

        Path out = Path.of("docs/verify-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(
                "verify: truth="
                        + truth.size()
                        + " predicted="
                        + predicted.size()
                        + " coverage="
                        + percent(totalMatched(perKind), truth.size()));
        System.out.println("report -> " + out.toAbsolutePath());
    }

    private static int totalMatched(Map<String, Bucket> perKind) {
        return perKind.values().stream().mapToInt(bucket -> bucket.matched).sum();
    }

    private static void collectFeatures(
            VanillaRuntime runtime,
            VersionRegistry versions,
            long seed,
            int centerX,
            int centerZ,
            int radius,
            Map<String, JsonObject> predicted,
            Map<String, Long> scanNanosPerStructure,
            VanillaLootOracle oracle) {
        for (var entry : versions.sets().entrySet()) {
            if (entry.getValue().placement().type()
                    != dev.lootradar.core.structure.PlacementSpec.Type.FEATURE) {
                continue;
            }
            for (var member : entry.getValue().structures()) {
                var spec = versions.structure(member.id());
                String featureId = spec.id();
                Integer rarity = spec.rarity();
                int radiusChunks = Math.floorDiv(radius, 16) + 1;
                int centerChunkX = Math.floorDiv(centerX, 16);
                int centerChunkZ = Math.floorDiv(centerZ, 16);
                for (int chunkX = centerChunkX - radiusChunks;
                        chunkX <= centerChunkX + radiusChunks;
                        chunkX++) {
                    for (int chunkZ = centerChunkZ - radiusChunks;
                            chunkZ <= centerChunkZ + radiusChunks;
                            chunkZ++) {
                        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                        double dx = chunk.getMinBlockX() + 8 - centerX;
                        double dz = chunk.getMinBlockZ() + 8 - centerZ;
                        if (dx * dx + dz * dz > (double) radius * radius) {
                            continue;
                        }
                        long startedAt = System.nanoTime();
                        if (rarity == null
                                || FeatureScanner.rarityAllows(
                                        runtime, featureId, spec.dimension(), chunk, rarity)) {
                            for (ContainerRecord record :
                                    FeatureScanner.scan(
                                            runtime, featureId, spec.dimension(), chunk)) {
                                predicted.put(
                                        posKey(record), toJson(oracle, record, spec.dimension()));
                            }
                        }
                        scanNanosPerStructure.merge(
                                featureId, System.nanoTime() - startedAt, Long::sum);
                    }
                }
            }
        }
    }

    private static String posKey(JsonArray pos) {
        return pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt();
    }

    private static String posKey(ContainerRecord record) {
        return record.pos().getX() + "," + record.pos().getY() + "," + record.pos().getZ();
    }

    private static boolean sameString(JsonObject a, JsonObject b, String key) {
        return str(a, key).equals(str(b, key));
    }

    private static boolean sameLong(JsonObject a, JsonObject b, String key) {
        long left = a.has(key) ? a.get(key).getAsLong() : 0;
        long right = b.has(key) ? b.get(key).getAsLong() : 0;
        return left == right;
    }

    private static boolean contentMatches(JsonObject truth, JsonObject predicted) {
        if (!truth.has("items") || !predicted.has("items")) {
            return true;
        }
        return multiset(truth.getAsJsonArray("items"))
                .equals(multiset(predicted.getAsJsonArray("items")));
    }

    private static Map<String, Integer> multiset(JsonArray items) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String key = item.get("item").getAsString() + " x" + item.get("count").getAsInt();
            map.merge(key, 1, Integer::sum);
        }
        return map;
    }

    private static String str(JsonObject row, String key) {
        return row.has(key) ? row.get(key).getAsString() : "";
    }

    private static String percent(int part, int total) {
        if (total == 0) {
            return "—";
        }
        return String.format("%.1f%%", 100.0 * part / total);
    }

    private static String millis(long nanos) {
        return String.format("%.1f ms", nanos / 1_000_000.0);
    }

    private static JsonObject toJson(
            VanillaLootOracle oracle, ContainerRecord record, String dimension) {
        JsonObject row = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(record.pos().getX());
        pos.add(record.pos().getY());
        pos.add(record.pos().getZ());
        row.add("pos", pos);
        row.addProperty("kind", record.kind().name().toLowerCase());
        row.addProperty("table", record.lootTable());
        row.addProperty("seed", record.lootSeed());
        if (record.lootSeed() != 0
                && !record.lootTable().isEmpty()
                && record.kind() != dev.lootradar.engine.ContainerKind.VAULT) {
            List<LootStack> items =
                    oracle.roll(
                            record.lootTable(),
                            record.lootSeed(),
                            dimension,
                            net.minecraft.world.phys.Vec3.atCenterOf(record.pos()));
            JsonArray array = new JsonArray();
            for (LootStack stack : items) {
                JsonObject item = new JsonObject();
                item.addProperty("item", stack.itemId());
                item.addProperty("count", stack.count());
                array.add(item);
            }
            row.add("items", array);
        }
        return row;
    }
}
