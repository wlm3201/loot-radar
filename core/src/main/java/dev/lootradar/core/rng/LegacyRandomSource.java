package dev.lootradar.core.rng;

/** 原版 {@code LegacyRandomSource}：与 {@code java.util.Random} 同款的 48 位 LCG。 */
public final class LegacyRandomSource implements BitRandom {

    // 三个常量与原版一致；setSeed 先与乘数异或，再截断到 48 位。
    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;
    private static final long MASK = (1L << 48) - 1;

    private long seed;

    public LegacyRandomSource(long seed) {
        setSeed(seed);
    }

    public void setSeed(long seed) {
        this.seed = (seed ^ MULTIPLIER) & MASK;
    }

    @Override
    public int next(int bits) {
        seed = (seed * MULTIPLIER + INCREMENT) & MASK;
        return (int) (seed >> (48 - bits));
    }
}
