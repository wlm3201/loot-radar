package dev.lootradar.core.rng;

/**
 * 原版 {@code XoroshiroRandomSource} 的镜像。
 *
 * <p>{@code nextInt(bound)} 使用 Lemire 乘高与拒绝采样，与 {@link BitRandom} 的取模式实现不同；宝库、远古守卫者等
 * random_sequence 序列依赖这一差异。
 */
public final class XoroshiroRandomSource {

    private Xoroshiro128PlusPlus generator;

    public XoroshiroRandomSource(long seed) {
        this(new Xoroshiro128PlusPlus(RandomSupport.upgradeSeedTo128bit(seed)));
    }

    public XoroshiroRandomSource(RandomSupport.Seed128bit seed) {
        this(new Xoroshiro128PlusPlus(seed));
    }

    public XoroshiroRandomSource(long seedLo, long seedHi) {
        this(new Xoroshiro128PlusPlus(seedLo, seedHi));
    }

    private XoroshiroRandomSource(Xoroshiro128PlusPlus generator) {
        this.generator = generator;
    }

    public void setSeed(long seed) {
        this.generator = new Xoroshiro128PlusPlus(RandomSupport.upgradeSeedTo128bit(seed));
    }

    public long nextLong() {
        return generator.nextLong();
    }

    public int nextInt() {
        return (int) generator.nextLong();
    }

    // 原版 Lemire 实现；不能替换成 BitRandom 的取模/拒绝采样版本。
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        long randomBits = Integer.toUnsignedLong(nextInt());
        long multiplied = randomBits * bound;
        long fractionalPart = multiplied & 0xFFFFFFFFL;
        if (fractionalPart < bound) {
            for (int threshold = Integer.remainderUnsigned(~bound + 1, bound);
                    fractionalPart < threshold;
                    fractionalPart = multiplied & 0xFFFFFFFFL) {
                randomBits = Integer.toUnsignedLong(nextInt());
                multiplied = randomBits * bound;
            }
        }
        return (int) (multiplied >> 32);
    }

    public float nextFloat() {
        return nextBits(24) * 5.9604645E-8F;
    }

    public double nextDouble() {
        return nextBits(53) * 0x1.0p-53;
    }

    public void consumeCount(int rounds) {
        for (int i = 0; i < rounds; i++) {
            generator.nextLong();
        }
    }

    private long nextBits(int bits) {
        return generator.nextLong() >>> (64 - bits);
    }
}
