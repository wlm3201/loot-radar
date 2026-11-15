package dev.lootradar.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;

/**
 * 要塞专项扫描。原版 {@code StrongholdPieces} 含静态状态，所有相关调用都在 类锁内串行执行；{@code selective} 模式只放置含战利品箱子的区块及其
 * 「会改变后续矿井/特征状态」的前置区块，其余区块跳过以节省时间。
 */
final class StrongholdScanner {
    private StrongholdScanner() {}

    static List<ContainerRecord> scan(
            VanillaRuntime runtime, String dimension, StructureStart start) {
        return scan(runtime, dimension, start, true);
    }

    static List<ContainerRecord> scan(
            VanillaRuntime runtime, String dimension, StructureStart start, boolean selective) {
        synchronized (StrongholdPieces.class) {
            Set<ChunkPos> wanted = lootChunks(runtime, start);
            List<ChunkPos> chunks =
                    start.getBoundingBox()
                            .intersectingChunks()
                            .sorted(
                                    Comparator.comparingInt(ChunkPos::x)
                                            .thenComparingInt(ChunkPos::z))
                            .toList();
            var terrain = new NativeTerrainCache(512);
            var mines = new PreStructureMineshafts(runtime, dimension);
            Set<ChunkPos> required =
                    selective ? mines.requiredStateChunks(chunks, wanted) : Set.copyOf(chunks);
            var coordinates = runtime.structureDecorationCoordinates("minecraft:stronghold");
            List<ContainerRecord> result = new ArrayList<>();
            for (ChunkPos chunk : chunks) {
                if (Thread.currentThread().isInterrupted()) break;
                if (!required.contains(chunk)) continue;
                var recording = new RecordingLevel(runtime, dimension, List.of(start), terrain);
                recording.setDecorationCenter(chunk);
                PreStructureFeatures.place(runtime, dimension, recording, chunk, 3);
                mines.place(recording, chunk);
                PreStructureFeatures.placeRange(
                        runtime, dimension, recording, chunk, 3, coordinates.step());
                if (!wanted.contains(chunk) && selective) continue;
                var manager =
                        new StructureManager(
                                recording.level(),
                                new WorldOptions(runtime.worldSeed(), true, false),
                                null);
                var random =
                        DecorationStreams.placement(
                                runtime.worldSeed(),
                                chunk.getMinBlockX(),
                                chunk.getMinBlockZ(),
                                coordinates.indexWithinStep(),
                                coordinates.step());
                var bounds =
                        new BoundingBox(
                                chunk.getMinBlockX(),
                                recording.level().getMinY(),
                                chunk.getMinBlockZ(),
                                chunk.getMaxBlockX(),
                                recording.level().getMaxY(),
                                chunk.getMaxBlockZ());
                start.placeInChunk(
                        recording.level(),
                        manager,
                        runtime.chunkGenerator(dimension),
                        random,
                        bounds,
                        chunk);
                recording.lootSources().stream()
                        .filter(
                                record ->
                                        record.lootTable()
                                                .startsWith("minecraft:chests/stronghold_"))
                        .forEach(result::add);
            }
            return List.copyOf(result);
        }
    }

    static Set<ChunkPos> lootChunks(VanillaRuntime runtime, StructureStart start) {
        Set<ChunkPos> result = new HashSet<>();
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof StrongholdPieces.ChestCorridor) {
                result.add(ChunkPos.containing(worldPos(piece, 3, 2, 3)));
            } else if (piece instanceof StrongholdPieces.RoomCrossing
                    && piece.createTag(runtime.structurePieceContext()).getIntOr("Type", 0) == 2) {
                result.add(ChunkPos.containing(worldPos(piece, 3, 4, 8)));
            } else if (piece instanceof StrongholdPieces.Library) {
                result.add(ChunkPos.containing(worldPos(piece, 3, 3, 5)));
                if (piece.getBoundingBox().getYSpan() > 6)
                    result.add(ChunkPos.containing(worldPos(piece, 12, 8, 1)));
            }
        }
        return Set.copyOf(result);
    }

    private static BlockPos worldPos(StructurePiece piece, int x, int y, int z) {
        var orientation = piece.getOrientation();
        var box = piece.getBoundingBox();
        if (orientation == null) return new BlockPos(x, y, z);
        int worldX =
                switch (orientation) {
                    case NORTH, SOUTH -> box.minX() + x;
                    case WEST -> box.maxX() - z;
                    case EAST -> box.minX() + z;
                    default -> x;
                };
        int worldZ =
                switch (orientation) {
                    case NORTH -> box.maxZ() - z;
                    case SOUTH -> box.minZ() + z;
                    case WEST, EAST -> box.minZ() + x;
                    default -> z;
                };
        return new BlockPos(worldX, box.minY() + y, worldZ);
    }
}
