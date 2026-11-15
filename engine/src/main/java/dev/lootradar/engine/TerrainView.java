package dev.lootradar.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

interface TerrainView {
    net.minecraft.world.level.chunk.ChunkAccess chunk(net.minecraft.world.level.ChunkPos pos);

    BlockState blockState(BlockPos pos);

    int height(Heightmap.Types type, int x, int z);
}
