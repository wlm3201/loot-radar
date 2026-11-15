package dev.lootradar.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * 重放目标结构/特征之前的所有装饰阶段特征。
 *
 * <p>装饰随机流是按阶段共享的，少放一个特征就会让后续放置的随机数整体前移； 这里按 {@code FeatureSorter} 的阶段与索引顺序逐个放置，只保留可能影响 群系集合的 3×3
 * 区块范围，以控制开销。
 */
final class PreStructureFeatures {

    private PreStructureFeatures() {}

    static void place(
            VanillaRuntime runtime,
            String dimension,
            RecordingLevel recording,
            ChunkPos center,
            int beforeStep) {
        placeRange(runtime, dimension, recording, center, 0, beforeStep);
    }

    static void placeRange(
            VanillaRuntime runtime,
            String dimension,
            RecordingLevel recording,
            ChunkPos center,
            int firstStep,
            int beforeStep) {
        if (beforeStep <= firstStep) return;

        ChunkGenerator generator = runtime.chunkGenerator(dimension);
        List<FeatureSorter.StepFeatureData> featureSteps = runtime.featuresPerStep(dimension);
        Set<Holder<Biome>> possibleBiomes = new HashSet<>();
        ChunkPos.rangeClosed(center, 1)
                .forEach(pos -> possibleBiomes.addAll(recording.biomesInChunk(pos)));
        possibleBiomes.retainAll(generator.getBiomeSource().possibleBiomes());

        BlockPos origin = SectionPos.of(center, recording.level().getMinSectionY()).origin();
        var stream = DecorationStreams.open(runtime.worldSeed(), origin.getX(), origin.getZ());
        WorldgenRandom random = stream.random();
        long decorationSeed = stream.decorationSeed();
        var featureRegistry = runtime.registries().lookupOrThrow(Registries.PLACED_FEATURE);

        try {
            for (int step = firstStep; step < beforeStep && step < featureSteps.size(); step++) {
                FeatureSorter.StepFeatureData stepData = featureSteps.get(step);
                Set<Integer> indices = new TreeSet<>();
                for (Holder<Biome> biome : possibleBiomes) {
                    var featuresInBiome = generator.getBiomeGenerationSettings(biome).features();
                    if (step >= featuresInBiome.size()) continue;
                    for (Holder<PlacedFeature> feature : featuresInBiome.get(step)) {
                        indices.add(stepData.indexMapping().applyAsInt(feature.value()));
                    }
                }

                for (int index : indices) {
                    PlacedFeature feature = stepData.features().get(index);
                    recording
                            .level()
                            .setCurrentlyGenerating(
                                    () ->
                                            featureRegistry
                                                    .getResourceKey(feature)
                                                    .map(Object::toString)
                                                    .orElseGet(feature::toString));
                    random.setFeatureSeed(decorationSeed, index, step);
                    try {
                        MinecraftVersionAdapter.placeFeature(
                                feature, recording.level(), generator, random, origin);
                    } catch (RuntimeException exception) {
                        throw new IllegalStateException(
                                "Pre-structure feature placement failed at "
                                        + center
                                        + " step="
                                        + step
                                        + " index="
                                        + index
                                        + " feature="
                                        + featureRegistry
                                                .getResourceKey(feature)
                                                .map(Object::toString)
                                                .orElseGet(feature::toString),
                                exception);
                    }
                }
            }
        } finally {
            recording.level().setCurrentlyGenerating(null);
        }
    }
}
