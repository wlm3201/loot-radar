package dev.lootradar.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * 把一个结构起点重放到 {@link RecordingLevel} 上，逐区块收集容器。
 *
 * <p>常规路径按包围盒覆盖的区块逐个建立独立的装饰随机流（与原版一致）； 沉船走 {@link TemplateFastPath}，要塞走 {@link
 * StrongholdScanner}，矿井还要 补放引用到该区块的其它矿井起点并维护蜘蛛网状态。HEIGHT_SENSITIVE 中的结构 必须开启原生噪声地形，否则贴地高度会算错。
 */
public final class StructureScanner {

    private StructureScanner() {}

    private static final java.util.Set<String> HEIGHT_SENSITIVE =
            java.util.Set.of(
                    "minecraft:shipwreck",
                    "minecraft:shipwreck_beached",
                    "minecraft:ocean_ruin_cold",
                    "minecraft:ocean_ruin_warm",
                    "minecraft:buried_treasure",
                    "minecraft:desert_pyramid",
                    "minecraft:igloo",
                    "minecraft:village_plains",
                    "minecraft:village_desert",
                    "minecraft:village_savanna",
                    "minecraft:village_snowy",
                    "minecraft:village_taiga",
                    "minecraft:pillager_outpost");

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime, String structureId, String dimensionId, StructureStart start) {
        return scan(runtime, structureId, dimensionId, start, null);
    }

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime,
            String structureId,
            String dimensionId,
            StructureStart start,
            java.util.Set<ChunkPos> outputChunks) {
        return scan(runtime, structureId, dimensionId, start, outputChunks, null);
    }

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime,
            String structureId,
            String dimensionId,
            StructureStart start,
            java.util.Set<ChunkPos> outputChunks,
            dev.lootradar.core.version.VersionRegistry data) {
        if (!MinecraftVersionAdapter.isValidStart(start)) {
            return List.of();
        }
        if (structureId.equals("minecraft:stronghold")) {
            return StrongholdScanner.scan(runtime, dimensionId, start);
        }
        if (TemplateFastPath.supports(structureId)) {
            List<ContainerRecord> fast =
                    TemplateFastPath.scan(runtime, dimensionId, structureId, start);
            if (fast != null) {
                return fast;
            }
        }
        VanillaRuntime.DecorationCoordinates decoration =
                runtime.structureDecorationCoordinates(
                        structureId, data == null ? null : data.structures().get(structureId));
        ChunkPos startChunk = start.getChunkPos();
        List<ChunkPos> chunks =
                start.getBoundingBox()
                        .intersectingChunks()
                        .sorted(
                                Comparator.comparingInt(
                                                (ChunkPos chunk) ->
                                                        chunk.equals(startChunk) ? 0 : 1)
                                        .thenComparingInt(ChunkPos::x)
                                        .thenComparingInt(ChunkPos::z))
                        .toList();

        List<ContainerRecord> result = new ArrayList<>();
        boolean mineshaft = MineshaftBiomes.isMineshaft(structureId);
        Map<ChunkPos, StructureStart> localStarts = new HashMap<>();
        Map<ChunkPos, List<BoundingBox>> spiderBoxes = new HashMap<>();
        localStarts.put(startChunk, start);
        for (ChunkPos chunk : chunks) {
            if (Thread.currentThread().isInterrupted()) break;
            List<ChunkPos> references =
                    mineshaft
                            ? runtime.mineshaftReferences(dimensionId)
                                    .startsForChunk(structureId, chunk)
                            : List.of();
            for (ChunkPos reference : references) {
                localStarts.computeIfAbsent(
                        reference, pos -> runtime.generateStructure(structureId, dimensionId, pos));
            }
            if (mineshaft && outputChunks != null && !outputChunks.contains(chunk)) {
                boolean affectsSpiderState = false;
                for (ChunkPos reference : references) {
                    var boxes =
                            spiderBoxes.computeIfAbsent(
                                    reference,
                                    pos ->
                                            localStarts.get(pos).getPieces().stream()
                                                    .filter(
                                                            piece ->
                                                                    piece
                                                                                    instanceof
                                                                                    net.minecraft
                                                                                            .world
                                                                                            .level
                                                                                            .levelgen
                                                                                            .structure
                                                                                            .structures
                                                                                            .MineshaftPieces
                                                                                            .MineShaftCorridor
                                                                            && piece.createTag(
                                                                                            runtime
                                                                                                    .structurePieceContext())
                                                                                    .getBooleanOr(
                                                                                            "sc",
                                                                                            false))
                                                    .map(piece -> piece.getBoundingBox())
                                                    .toList());
                    if (boxes.stream()
                            .anyMatch(
                                    box ->
                                            box.intersects(
                                                    chunk.getMinBlockX(),
                                                    chunk.getMinBlockZ(),
                                                    chunk.getMaxBlockX(),
                                                    chunk.getMaxBlockZ())))
                        affectsSpiderState = true;
                }
                if (!affectsSpiderState) continue;
            }
            RecordingLevel recording =
                    new RecordingLevel(
                            runtime,
                            dimensionId,
                            structureId.equals("minecraft:mineshaft")
                                    || structureId.equals("minecraft:mineshaft_mesa")
                                    || HEIGHT_SENSITIVE.contains(structureId));
            recording.setDecorationCenter(chunk);
            if (mineshaft)
                PreStructureFeatures.place(
                        runtime, dimensionId, recording, chunk, decoration.step());
            StructureManager structureManager =
                    new StructureManager(
                            recording.level(),
                            new WorldOptions(runtime.worldSeed(), true, false),
                            null);
            WorldgenRandom random =
                    DecorationStreams.placement(
                            runtime.worldSeed(),
                            chunk.getMinBlockX(),
                            chunk.getMinBlockZ(),
                            decoration.indexWithinStep(),
                            decoration.step());
            BoundingBox bounds =
                    new BoundingBox(
                            chunk.getMinBlockX(),
                            runtime.heightAccessor(dimensionId).getMinY(),
                            chunk.getMinBlockZ(),
                            chunk.getMaxBlockX(),
                            runtime.heightAccessor(dimensionId).getMaxY(),
                            chunk.getMaxBlockZ());
            try {
                if (mineshaft) {
                    for (ChunkPos reference : references) {
                        StructureStart referencedStart = localStarts.get(reference);
                        int before = recording.lootSources().size();
                        referencedStart.placeInChunk(
                                recording.level(),
                                structureManager,
                                runtime.chunkGenerator(dimensionId),
                                random,
                                bounds,
                                chunk);
                        if (reference.equals(startChunk)
                                && (outputChunks == null || outputChunks.contains(chunk))) {
                            var after = recording.lootSources();
                            result.addAll(after.subList(before, after.size()));
                        }
                    }
                } else {
                    start.placeInChunk(
                            recording.level(),
                            structureManager,
                            runtime.chunkGenerator(dimensionId),
                            random,
                            bounds,
                            chunk);
                    result.addAll(recording.lootSources());
                }
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "Vanilla placement recording failed for "
                                + structureId
                                + " start="
                                + startChunk
                                + " decoration="
                                + chunk,
                        exception);
            }
        }
        return List.copyOf(result);
    }
}
