package dev.lootradar.engine;

import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * 对 placed feature 型来源（沙漠水井等）做一次独立的放置重放。
 *
 * <p>{@code rarityAllows} 复刻原版 {@code RarityFilter.onAverageOnceEvery(rarity)}： 用同一条装饰流的 {@code
 * nextFloat() < 1/rarity} 判定是否真的生成。
 */
public final class FeatureScanner {

    private FeatureScanner() {}

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime, String featureId, String dimensionId, ChunkPos chunk) {
        return scan(runtime, featureId, dimensionId, chunk, null);
    }

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime,
            String featureId,
            String dimensionId,
            ChunkPos chunk,
            dev.lootradar.core.version.VersionRegistry data) {
        if (featureId.equals("minecraft:monster_room")) {
            return DungeonScanner.scan(runtime, dimensionId, chunk, data);
        }
        VanillaRuntime.FeatureCoordinates coords =
                runtime.featureDecorationCoordinates(
                        featureId,
                        dimensionId,
                        data == null ? null : data.structures().get(featureId));
        RecordingLevel recording = new RecordingLevel(runtime, dimensionId, true);
        WorldgenRandom random =
                DecorationStreams.placement(
                        runtime.worldSeed(),
                        chunk.getMinBlockX(),
                        chunk.getMinBlockZ(),
                        coords.indexWithinStep(),
                        coords.step());
        var origin = new net.minecraft.core.BlockPos(chunk.getMinBlockX(), 0, chunk.getMinBlockZ());

        try {
            MinecraftVersionAdapter.placeFeature(
                    runtime.placedFeature(featureId),
                    recording.level(),
                    runtime.chunkGenerator(dimensionId),
                    random,
                    origin);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "feature placement recording failed for " + featureId + " at " + chunk,
                    exception);
        }
        return recording.lootSources();
    }

    public static boolean rarityAllows(
            VanillaRuntime runtime,
            String featureId,
            String dimensionId,
            ChunkPos chunk,
            int rarity) {
        return rarityAllows(runtime, featureId, dimensionId, chunk, rarity, null);
    }

    public static boolean rarityAllows(
            VanillaRuntime runtime,
            String featureId,
            String dimensionId,
            ChunkPos chunk,
            int rarity,
            dev.lootradar.core.version.VersionRegistry data) {
        VanillaRuntime.FeatureCoordinates coords =
                runtime.featureDecorationCoordinates(
                        featureId,
                        dimensionId,
                        data == null ? null : data.structures().get(featureId));
        WorldgenRandom random =
                DecorationStreams.placement(
                        runtime.worldSeed(),
                        chunk.getMinBlockX(),
                        chunk.getMinBlockZ(),
                        coords.indexWithinStep(),
                        coords.step());
        return random.nextFloat() < 1.0f / rarity;
    }
}
