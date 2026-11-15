package dev.lootradar.core.structure;

import dev.lootradar.core.rng.WorldgenRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版 {@code RandomSpreadStructurePlacement} 的纯 Java 复刻，不依赖 Minecraft 运行时。
 *
 * <p>一个 spacing×spacing 的网格单元内最多有一个候选起始区块；只有再通过频率缩减 与 exclusion zone 检查，才算真正会生成结构。频率 reducer
 * 的四种实现分别对应原版 的 default / legacy_type_1 / legacy_type_2 / legacy_type_3，且都使用 Legacy LCG。
 */
public final class RandomSpreadLocator {

    public record Candidate(int chunkX, int chunkZ) {}

    private RandomSpreadLocator() {}

    public static Candidate potentialChunk(
            PlacementSpec placement, long worldSeed, int gridX, int gridZ) {
        if (!placement.isRandomSpread()) {
            throw new IllegalArgumentException(
                    "not a random-spread placement: " + placement.type());
        }
        WorldgenRandom random = WorldgenRandom.legacy();
        random.setLargeFeatureWithSalt(worldSeed, gridX, gridZ, placement.salt());
        int limit = placement.spacing() - placement.separation();
        int spreadX = evaluate(placement.spreadType(), random, limit);
        int spreadZ = evaluate(placement.spreadType(), random, limit);
        return new Candidate(
                gridX * placement.spacing() + spreadX, gridZ * placement.spacing() + spreadZ);
    }

    // triangular 两次 nextInt(limit) 取平均，等价于原版 RandomSpreadType.TRIANGULAR。
    private static int evaluate(String spreadType, WorldgenRandom random, int limit) {
        return "triangular".equals(spreadType)
                ? (random.nextInt(limit) + random.nextInt(limit)) / 2
                : random.nextInt(limit);
    }

    public static Candidate potentialChunkForChunk(
            PlacementSpec placement, long worldSeed, int chunkX, int chunkZ) {
        return potentialChunk(
                placement,
                worldSeed,
                Math.floorDiv(chunkX, placement.spacing()),
                Math.floorDiv(chunkZ, placement.spacing()));
    }

    // frequency >= 1 直接放行；否则按数据文件里的 reducer 名称选择原版历史逻辑。
    public static boolean frequencyAllows(
            PlacementSpec placement, long worldSeed, int chunkX, int chunkZ) {
        if (placement.frequency() >= 1.0f) {
            return true;
        }
        float probability = placement.frequency();
        return switch (placement.frequencyReductionMethod()) {
            case "legacy_type_1" ->
                    legacyPillagerOutpostReducer(worldSeed, chunkX, chunkZ, probability);
            case "legacy_type_2" ->
                    legacyArbitrarySaltReducer(worldSeed, chunkX, chunkZ, probability);
            case "legacy_type_3" -> legacyDoubleReducer(worldSeed, chunkX, chunkZ, probability);
            default -> defaultReducer(worldSeed, placement.salt(), chunkX, chunkZ, probability);
        };
    }

    private static boolean defaultReducer(
            long worldSeed, int salt, int chunkX, int chunkZ, float probability) {
        WorldgenRandom random = WorldgenRandom.legacy();
        random.setLargeFeatureWithSalt(worldSeed, salt, chunkX, chunkZ);
        return random.nextFloat() < probability;
    }

    // 前哨站专用：区块坐标先 >>4，setSeed 后丢弃一次 nextInt()，再判断 nextInt(1/p)==0。
    private static boolean legacyPillagerOutpostReducer(
            long worldSeed, int chunkX, int chunkZ, float probability) {
        int cx = chunkX >> 4;
        int cz = chunkZ >> 4;
        WorldgenRandom random = WorldgenRandom.legacy();
        random.setSeed(cx ^ (cz << 4) ^ worldSeed);
        random.nextInt();
        return random.nextInt((int) (1.0F / probability)) == 0;
    }

    private static boolean legacyArbitrarySaltReducer(
            long worldSeed, int chunkX, int chunkZ, float probability) {
        WorldgenRandom random = WorldgenRandom.legacy();
        random.setLargeFeatureWithSalt(worldSeed, chunkX, chunkZ, 10387320);
        return random.nextFloat() < probability;
    }

    private static boolean legacyDoubleReducer(
            long worldSeed, int chunkX, int chunkZ, float probability) {
        WorldgenRandom random = WorldgenRandom.legacy();
        random.setLargeFeatureSeed(worldSeed, chunkX, chunkZ);
        return random.nextDouble() < probability;
    }

    public static boolean isStructureChunk(
            StructureSetSpec set,
            long worldSeed,
            int chunkX,
            int chunkZ,
            Map<String, StructureSetSpec> allSets) {
        PlacementSpec placement = set.placement();
        if (!placement.isRandomSpread()) {
            throw new IllegalArgumentException("not a random-spread set: " + set.id());
        }
        int gridX = Math.floorDiv(chunkX, placement.spacing());
        int gridZ = Math.floorDiv(chunkZ, placement.spacing());
        Candidate candidate = potentialChunk(placement, worldSeed, gridX, gridZ);
        if (candidate.chunkX() != chunkX || candidate.chunkZ() != chunkZ) {
            return false;
        }
        if (!frequencyAllows(placement, worldSeed, chunkX, chunkZ)) {
            return false;
        }
        PlacementSpec.ExclusionZone zone = placement.exclusionZone();
        if (zone != null) {
            StructureSetSpec other = allSets.get(zone.otherSet());
            if (other == null) {
                throw new IllegalArgumentException(
                        "exclusion zone references unknown set " + zone.otherSet());
            }
            for (int testX = chunkX - zone.chunkCount();
                    testX <= chunkX + zone.chunkCount();
                    testX++) {
                for (int testZ = chunkZ - zone.chunkCount();
                        testZ <= chunkZ + zone.chunkCount();
                        testZ++) {
                    if (isStructureChunk(other, worldSeed, testX, testZ, allSets)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static List<Candidate> candidatesInRadius(
            StructureSetSpec set,
            long worldSeed,
            int centerChunkX,
            int centerChunkZ,
            int radiusChunks,
            Map<String, StructureSetSpec> allSets) {
        if (!set.placement().isRandomSpread()) {
            throw new IllegalArgumentException("not a random-spread set: " + set.id());
        }
        List<Candidate> result = new ArrayList<>();
        int spacing = set.placement().spacing();
        int minGridX = Math.floorDiv(centerChunkX - radiusChunks, spacing);
        int maxGridX = Math.floorDiv(centerChunkX + radiusChunks, spacing);
        int minGridZ = Math.floorDiv(centerChunkZ - radiusChunks, spacing);
        int maxGridZ = Math.floorDiv(centerChunkZ + radiusChunks, spacing);
        for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
            for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
                Candidate candidate = potentialChunk(set.placement(), worldSeed, gridX, gridZ);
                if (Math.abs(candidate.chunkX() - centerChunkX) > radiusChunks
                        || Math.abs(candidate.chunkZ() - centerChunkZ) > radiusChunks) {
                    continue;
                }
                if (isStructureChunk(
                        set, worldSeed, candidate.chunkX(), candidate.chunkZ(), allSets)) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }
}
