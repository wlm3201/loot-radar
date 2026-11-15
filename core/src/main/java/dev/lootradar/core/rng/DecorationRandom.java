package dev.lootradar.core.rng;

/**
 * 不依赖 Minecraft 运行时的「装饰种子 → 容器种子」简化模型。
 *
 * <p>仅供 core 单测与验证脚本使用；线上引擎走 {@code DecorationStreams} + 原生 {@code
 * WorldgenRandom}，以便完整重放结构生成过程中的每一次随机消耗。
 */
public final class DecorationRandom {

    private final WorldgenRandom random = WorldgenRandom.xoroshiro();

    public long setDecorationSeed(long worldSeed, int chunkX, int chunkZ) {
        return random.setDecorationSeed(worldSeed, chunkX * 16, chunkZ * 16);
    }

    public void setFeatureSeed(long decorationSeed, int structureIndex, int decorationStep) {
        random.setFeatureSeed(decorationSeed, structureIndex, decorationStep);
    }

    public long nextLong() {
        return random.nextLong();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    // 容器序号 = 同一 (chunk, index, step) 流上第 n 次 nextLong()。
    // 仅在「该流只被容器抽奖消耗」的理想情形成立，实际结构需完整重放放置过程。
    public static long containerLootSeed(
            long worldSeed,
            int chunkX,
            int chunkZ,
            int structureIndex,
            int decorationStep,
            int containerOrdinal) {
        if (containerOrdinal < 0) {
            throw new IllegalArgumentException("container ordinal must be non-negative");
        }
        DecorationRandom random = new DecorationRandom();
        long decorationSeed = random.setDecorationSeed(worldSeed, chunkX, chunkZ);
        random.setFeatureSeed(decorationSeed, structureIndex, decorationStep);
        long result = 0L;
        for (int index = 0; index <= containerOrdinal; index++) {
            result = random.nextLong();
        }
        return result;
    }
}
