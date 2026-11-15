package dev.lootradar.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;

/**
 * 硫黄洞穴废弃矿井（Bounce 唱片）判定：走廊每 5 格有两个可能生成箱子矿车的槽位， 只有落点群系为 {@code minecraft:sulfur_caves} 的区块才可能产出
 * Bounce。
 */
public final class MineshaftBiomes {
    private static final Identifier SULFUR = Identifier.parse("minecraft:sulfur_caves");

    private MineshaftBiomes() {}

    public static boolean isMineshaft(String id) {
        return id.equals("minecraft:mineshaft") || id.equals("minecraft:mineshaft_mesa");
    }

    public static boolean mayContainBounce(
            VanillaRuntime runtime, String dimension, StructureStart start) {
        return !bounceChunks(runtime, dimension, start).isEmpty();
    }

    public static java.util.Set<net.minecraft.world.level.ChunkPos> bounceChunks(
            VanillaRuntime runtime, String dimension, StructureStart start) {
        var chunks = new java.util.HashSet<net.minecraft.world.level.ChunkPos>();
        for (var piece : start.getPieces()) {
            if (!(piece instanceof MineshaftPieces.MineShaftCorridor)) continue;
            var box = piece.getBoundingBox();
            Direction facing = piece.getOrientation();
            int length = facing.getAxis() == Direction.Axis.Z ? box.getZSpan() : box.getXSpan();
            for (int section = 0; section < length / 5; section++) {
                for (int slot = 0; slot < 2; slot++) {
                    int localX = slot == 0 ? 2 : 0;
                    int localZ = section * 5 + (slot == 0 ? 1 : 3);
                    int x =
                            switch (facing) {
                                case NORTH, SOUTH -> box.minX() + localX;
                                case WEST -> box.maxX() - localZ;
                                case EAST -> box.minX() + localZ;
                                default ->
                                        throw new IllegalStateException(
                                                "Non-horizontal mineshaft corridor");
                            };
                    int z =
                            switch (facing) {
                                case NORTH -> box.maxZ() - localZ;
                                case SOUTH -> box.minZ() + localZ;
                                case WEST, EAST -> box.minZ() + localX;
                                default ->
                                        throw new IllegalStateException(
                                                "Non-horizontal mineshaft corridor");
                            };
                    if (runtime.biomeAt(dimension, new BlockPos(x, box.minY(), z)).is(SULFUR)) {
                        chunks.add(new net.minecraft.world.level.ChunkPos(x >> 4, z >> 4));
                    }
                }
            }
        }
        return java.util.Set.copyOf(chunks);
    }
}
