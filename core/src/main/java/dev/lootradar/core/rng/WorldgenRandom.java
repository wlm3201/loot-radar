package dev.lootradar.core.rng;

/**
 * 原版 {@code WorldgenRandom} 的镜像：一次只持有 LCG 或 Xoroshiro 一种底层随机源。
 *
 * <p>四个 {@code setXxxSeed} 公式是「装饰种子 → 特征种子 → 箱子 lootTableSeed」 整条链的起点；改动任一系数都会使所有结构的预测结果失效。
 */
public final class WorldgenRandom implements BitRandom {

    private final LegacyRandomSource legacy;
    private final XoroshiroRandomSource xoroshiro;

    public static WorldgenRandom legacy() {
        return new WorldgenRandom(new LegacyRandomSource(0L), null);
    }

    public static WorldgenRandom xoroshiro() {
        return new WorldgenRandom(null, new XoroshiroRandomSource(0L));
    }

    private WorldgenRandom(LegacyRandomSource legacy, XoroshiroRandomSource xoroshiro) {
        this.legacy = legacy;
        this.xoroshiro = xoroshiro;
    }

    @Override
    public int next(int bits) {
        if (legacy != null) {
            return legacy.next(bits);
        }
        return (int) (xoroshiro.nextLong() >>> (64 - bits));
    }

    public void setSeed(long seed) {
        if (legacy != null) {
            legacy.setSeed(seed);
        } else {
            xoroshiro.setSeed(seed);
        }
    }

    // 优先级：先算 blockX*xScale + blockZ*zScale，再与 worldSeed 异或；
    // 两个缩放因子强制为奇数，blockX/blockZ 是区块最小方块坐标（chunk*16）。
    public long setDecorationSeed(long worldSeed, int blockX, int blockZ) {
        setSeed(worldSeed);
        long xScale = nextLong() | 1L;
        long zScale = nextLong() | 1L;
        long result = blockX * xScale + blockZ * zScale ^ worldSeed;
        setSeed(result);
        return result;
    }

    // step 是装饰阶段序号，index 是该阶段内排序后的特征/结构下标。
    public void setFeatureSeed(long decorationSeed, int index, int step) {
        setSeed(decorationSeed + index + 10000L * step);
    }

    // 结构集权重选择与 legacy_type_3 频率判定；缩放因子取 nextLong() 原值，不强制奇数。
    public void setLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
        setSeed(worldSeed);
        long xScale = nextLong();
        long zScale = nextLong();
        long result = chunkX * xScale ^ chunkZ * zScale ^ worldSeed;
        setSeed(result);
    }

    // random_spread 网格与 default/legacy_type_2 频率判定；x/z/salt 的位置不可互换。
    public void setLargeFeatureWithSalt(long worldSeed, int x, int z, int salt) {
        setSeed(x * 341873128712L + z * 132897987541L + worldSeed + salt);
    }
}
