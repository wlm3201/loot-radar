package dev.lootradar.app.tools;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/** 诊断工具：打印指定 (seed, chunk, step, index) 装饰流的前 8 个 nextLong()。 */
public final class StreamProbe {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        var chunk = new ChunkPos(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        int step = Integer.parseInt(args[3]);
        int index = Integer.parseInt(args[4]);
        WorldgenRandom random =
                dev.lootradar.engine.DecorationStreams.placement(
                        seed, chunk.getMinBlockX(), chunk.getMinBlockZ(), index, step);
        for (int i = 0; i < 8; i++) {
            System.out.println("nextLong[" + i + "]=" + random.nextLong());
        }
    }
}
