package dev.lootradar.core.rng;

/**
 * 原版 {@code net.minecraft.world.level.levelgen.BitRandomSource} 默认方法的最小复刻。
 *
 * <p>{@code nextInt(bound)} 的「2 的幂走乘法、其余走拒绝采样」必须逐位一致：世界生成 与战利品表的随机流都由这里派生，任何舍入或溢出差异都会让预测结果整体错位。
 */
public interface BitRandom {

    int next(int bits);

    default int nextInt() {
        return next(32);
    }

    default int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        if ((bound & (bound - 1)) == 0) {
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int sample;
        int modulo;
        do {
            sample = next(31);
            modulo = sample % bound;
        } while (sample - modulo + (bound - 1) < 0);
        return modulo;
    }

    default long nextLong() {
        int upper = next(32);
        int lower = next(32);
        return ((long) upper << 32) + lower;
    }

    default boolean nextBoolean() {
        return next(1) != 0;
    }

    default float nextFloat() {
        return next(24) * 5.9604645E-8F;
    }

    default double nextDouble() {
        int upper = next(26);
        int lower = next(27);
        long combined = ((long) upper << 27) + lower;
        return combined * 0x1.0p-53;
    }
}
