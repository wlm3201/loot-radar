package dev.lootradar.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

/**
 * 诊断/校准工具：在放置要塞时用 {@code TracingXoroshiro} 记录随机数输出， 并在目标箱子被读取的位置附近枚举「相邻两个输出的高 32 位」拼成的候选种子。
 *
 * <p>只被 {@code StrongholdCandidatesTest} 使用，不参与线上搜索。
 */
final class StrongholdSeedCandidates {

    record Result(
            Map<Long, Integer> frequencies,
            int capturedIndex,
            int window,
            int streamLength,
            List<Long> outputs) {}

    private static final int WINDOW = 32;

    private StrongholdSeedCandidates() {}

    static Result enumerate(
            VanillaRuntime runtime, String dimension, StructureStart original, BlockPos target) {
        synchronized (StrongholdPieces.class) {
            CompoundTag startTag =
                    original.createTag(
                            runtime.structurePieceContext(), ChunkPos.containing(target));
            StructureStart start =
                    MinecraftVersionAdapter.loadStart(
                            runtime.structurePieceContext(),
                            startTag,
                            runtime.worldSeed(),
                            original.getStructure());
            if (start == null || !MinecraftVersionAdapter.isValidStart(start)) {
                return new Result(Map.of(), -1, WINDOW, 0, List.of());
            }
            ChunkPos targetChunk = ChunkPos.containing(target);
            var coordinates = runtime.structureDecorationCoordinates("minecraft:stronghold");
            var recording =
                    new RecordingLevel(
                            runtime, dimension, List.of(start), new NativeTerrainCache(512));
            recording.setDecorationCenter(targetChunk);
            PreStructureFeatures.place(runtime, dimension, recording, targetChunk, 3);
            new PreStructureMineshafts(runtime, dimension).place(recording, targetChunk);
            PreStructureFeatures.placeRange(
                    runtime, dimension, recording, targetChunk, 3, coordinates.step());

            TracingXoroshiro source = new TracingXoroshiro();
            WorldgenRandom random = new WorldgenRandom(source);
            long decorationSeed =
                    random.setDecorationSeed(
                            runtime.worldSeed(),
                            targetChunk.getMinBlockX(),
                            targetChunk.getMinBlockZ());
            random.setFeatureSeed(
                    decorationSeed, coordinates.indexWithinStep(), coordinates.step());
            int baseOutputs = source.outputs.size();
            int[] capturedIndex = {-1};
            recording.trackBlockEntityReads(
                    pos -> {
                        if (pos.equals(target)) {
                            source.lastWasChestLookup = true;
                        }
                    });
            WorldgenRandom capturing =
                    new WorldgenRandom(source) {
                        @Override
                        public long nextLong() {
                            boolean chestPending = source.lastWasChestLookup;
                            source.lastWasChestLookup = false;
                            long value = super.nextLong();
                            if (capturedIndex[0] < 0 && chestPending) {
                                capturedIndex[0] = source.outputs.size() - 1;
                            }
                            return value;
                        }
                    };
            var manager =
                    new StructureManager(
                            recording.level(),
                            new WorldOptions(runtime.worldSeed(), true, false),
                            null);
            var bounds =
                    new BoundingBox(
                            targetChunk.getMinBlockX(),
                            recording.level().getMinY(),
                            targetChunk.getMinBlockZ(),
                            targetChunk.getMaxBlockX(),
                            recording.level().getMaxY(),
                            targetChunk.getMaxBlockZ());
            start.placeInChunk(
                    recording.level(),
                    manager,
                    runtime.chunkGenerator(dimension),
                    capturing,
                    bounds,
                    targetChunk);

            List<Long> outputs = source.outputs;
            if (capturedIndex[0] < 1) {
                return new Result(Map.of(), -1, WINDOW, outputs.size(), List.copyOf(outputs));
            }
            int pairStart = capturedIndex[0] - 1 - baseOutputs;
            Map<Long, Integer> frequencies = new LinkedHashMap<>();
            int lo = Math.max(baseOutputs, capturedIndex[0] - 1 - 2 * WINDOW);
            int hi = Math.min(outputs.size() - 2, capturedIndex[0] - 1 + 2 * WINDOW);
            for (int index = lo; index <= hi; index++) {
                long seed = compose(outputs.get(index), outputs.get(index + 1));
                if (seed != 0L) {
                    frequencies.merge(seed, 1, Integer::sum);
                }
            }
            return new Result(
                    Map.copyOf(frequencies),
                    pairStart,
                    WINDOW,
                    outputs.size() - baseOutputs,
                    List.copyOf(outputs));
        }
    }

    private static long compose(long rawFirst, long rawSecond) {
        return ((long) (int) (rawFirst >>> 32) << 32) + (int) (rawSecond >>> 32);
    }

    private static final class TracingXoroshiro extends XoroshiroRandomSource {
        final List<Long> outputs = new ArrayList<>();
        boolean lastWasChestLookup;
        long capturedValue;

        TracingXoroshiro() {
            super(0L);
        }

        @Override
        public long nextLong() {
            long value = super.nextLong();
            outputs.add(value);
            return value;
        }
    }
}
