package dev.lootradar.core.structure;

import java.util.ArrayList;
import java.util.List;

/** 把以中心区块为圆心的方形范围切成固定大小的扫描分片，供覆盖率普查分批调度。 */
public final class ShardPlanner {

    public record Shard(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minChunkX
                    && chunkX <= maxChunkX
                    && chunkZ >= minChunkZ
                    && chunkZ <= maxChunkZ;
        }
    }

    private ShardPlanner() {}

    public static List<Shard> plan(
            int centerChunkX, int centerChunkZ, int radiusChunks, int shardSizeChunks) {
        if (radiusChunks < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        if (shardSizeChunks <= 0) {
            throw new IllegalArgumentException("shard size must be positive");
        }
        int minX = centerChunkX - radiusChunks;
        int maxX = centerChunkX + radiusChunks;
        int minZ = centerChunkZ - radiusChunks;
        int maxZ = centerChunkZ + radiusChunks;
        List<Shard> shards = new ArrayList<>();
        for (int x = minX; x <= maxX; x += shardSizeChunks) {
            for (int z = minZ; z <= maxZ; z += shardSizeChunks) {
                shards.add(
                        new Shard(
                                x,
                                z,
                                Math.min(x + shardSizeChunks - 1, maxX),
                                Math.min(z + shardSizeChunks - 1, maxZ)));
            }
        }
        return List.copyOf(shards);
    }
}
