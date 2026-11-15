package dev.lootradar.core.rng;

/** 原版 Xoroshiro128PlusPlus 内核：两个 64 位状态，全 0 种子会被替换为两个魔数。 */
public final class Xoroshiro128PlusPlus {

    private long seedLo;
    private long seedHi;

    public Xoroshiro128PlusPlus(RandomSupport.Seed128bit seed) {
        this(seed.seedLo(), seed.seedHi());
    }

    public Xoroshiro128PlusPlus(long seedLo, long seedHi) {
        this.seedLo = seedLo;
        this.seedHi = seedHi;
        if ((this.seedLo | this.seedHi) == 0L) {
            this.seedLo = RandomSupport.GOLDEN_RATIO_64;
            this.seedHi = RandomSupport.SILVER_RATIO_64;
        }
    }

    // 结果 = rotl(s0+s1,17)+s0；状态更新顺序不可调整，否则整条序列会偏移。
    public long nextLong() {
        long s0 = seedLo;
        long s1 = seedHi;
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        seedLo = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        seedHi = Long.rotateLeft(s1, 28);
        return result;
    }
}
