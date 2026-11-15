package dev.lootradar.engine;

import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * 打开原版装饰随机流：先 {@code setDecorationSeed(worldSeed, chunkMinX, chunkMinZ)}， 再按需 {@code
 * setFeatureSeed(decorationSeed, index, step)}。
 *
 * <p>所有结构/特征放置与箱子种子都从这条流派生，因此必须用原生 {@code WorldgenRandom(XoroshiroRandomSource)}，不能用 core 的简化模型替代。
 */
public final class DecorationStreams {

    public record Stream(WorldgenRandom random, long decorationSeed) {}

    private DecorationStreams() {}

    public static Stream open(long worldSeed, int blockX, int blockZ) {
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(worldSeed, blockX, blockZ);
        return new Stream(random, decorationSeed);
    }

    public static WorldgenRandom placement(
            long worldSeed, int blockX, int blockZ, int indexWithinStep, int step) {
        Stream stream = open(worldSeed, blockX, blockZ);
        stream.random().setFeatureSeed(stream.decorationSeed(), indexWithinStep, step);
        return stream.random();
    }
}
