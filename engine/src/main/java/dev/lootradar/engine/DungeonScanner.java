package dev.lootradar.engine;

import java.util.Comparator;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

/**
 * 刷怪房（monster_room / monster_room_deep）专项扫描。
 *
 * <p>两种 placed feature 属于同一装饰阶段，必须按 indexWithinStep 顺序依次重放； 在此之前还要补放更早阶段的特征与矿井，保证箱子种子取到的 {@code
 * nextLong()} 序号与真实世界一致。
 */
public final class DungeonScanner {
    private static final String TABLE = "minecraft:chests/simple_dungeon";
    private static final List<String> FEATURES =
            List.of("minecraft:monster_room", "minecraft:monster_room_deep");

    private DungeonScanner() {}

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime, String dimension, ChunkPos chunk) {
        return scan(runtime, dimension, chunk, null);
    }

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime,
            String dimension,
            ChunkPos chunk,
            dev.lootradar.core.version.VersionRegistry data) {
        RecordingLevel recording = new RecordingLevel(runtime, dimension, true);
        recording.setDecorationCenter(chunk);
        List<String> features =
                FEATURES.stream()
                        .sorted(
                                Comparator.comparingInt(
                                        feature ->
                                                coordinates(runtime, dimension, feature, data)
                                                        .indexWithinStep()))
                        .toList();
        int step = coordinates(runtime, dimension, features.getFirst(), data).step();
        PreStructureFeatures.place(runtime, dimension, recording, chunk, step);
        new PreStructureMineshafts(runtime, dimension).place(recording, chunk);

        var stream =
                DecorationStreams.open(
                        runtime.worldSeed(), chunk.getMinBlockX(), chunk.getMinBlockZ());
        var random = stream.random();
        long decorationSeed = stream.decorationSeed();
        var origin = SectionPos.of(chunk, recording.level().getMinSectionY()).origin();
        for (String feature : features) {
            var coordinates = coordinates(runtime, dimension, feature, data);
            if (coordinates.step() != step) {
                throw new IllegalStateException(
                        "Monster-room features must share a decoration step");
            }
            random.setFeatureSeed(
                    decorationSeed, coordinates.indexWithinStep(), coordinates.step());
            try {
                MinecraftVersionAdapter.placeFeature(
                        runtime.placedFeature(feature),
                        recording.level(),
                        runtime.chunkGenerator(dimension),
                        random,
                        origin);
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "Dungeon placement failed for " + feature + " at " + chunk, exception);
            }
        }
        return recording.lootSources().stream()
                .filter(source -> source.lootTable().equals(TABLE))
                .toList();
    }

    private static VanillaRuntime.FeatureCoordinates coordinates(
            VanillaRuntime runtime,
            String dimension,
            String feature,
            dev.lootradar.core.version.VersionRegistry data) {
        if (data != null) {
            var spec = data.structures().get("minecraft:monster_room");
            if (spec != null && feature.endsWith("_deep")) {
                if (spec.featureIndexDeep() != null && spec.featureStepDeep() != null) {
                    return new VanillaRuntime.FeatureCoordinates(
                            spec.featureStepDeep(), spec.featureIndexDeep());
                }
            } else if (spec != null && spec.featureIndex() != null && spec.featureStep() != null) {
                return new VanillaRuntime.FeatureCoordinates(
                        spec.featureStep(), spec.featureIndex());
            }
        }
        return runtime.featureDecorationCoordinates(feature, dimension);
    }
}
