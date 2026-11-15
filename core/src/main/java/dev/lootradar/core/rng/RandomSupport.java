package dev.lootradar.core.rng;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 原版 {@code RandomSupport} 的种子工具：Stafford 13 混合、64 位种子升位、标识符哈希。
 *
 * <p>线上引擎直接使用原生的 {@code RandomSequence}，本类主要作为 {@code tools/verify-rng-reference.py} 与原版公式对齐的回归基准。
 */
public final class RandomSupport {

    public static final long GOLDEN_RATIO_64 = -7046029254386353131L;
    public static final long SILVER_RATIO_64 = 7640891576956012809L;

    private RandomSupport() {}

    public static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * -4658895280553007687L;
        z = (z ^ (z >>> 27)) * -7723592293110705685L;
        return z ^ (z >>> 31);
    }

    // 低 64 位 = legacySeed ^ SILVER，高 64 位 = 低 64 位 + GOLDEN；mixed() 逐半字混合。
    public static Seed128bit upgradeSeedTo128bitUnmixed(long legacySeed) {
        long lowBits = legacySeed ^ SILVER_RATIO_64;
        long highBits = lowBits + GOLDEN_RATIO_64;
        return new Seed128bit(lowBits, highBits);
    }

    public static Seed128bit upgradeSeedTo128bit(long legacySeed) {
        return upgradeSeedTo128bitUnmixed(legacySeed).mixed();
    }

    // 原版用 Guava Hashing.md5() 取 16 字节，再以大端字节序拆成两个 long。
    public static Seed128bit seedFromHashOf(String input) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
        long hashLo = fromBytes(hash, 0);
        long hashHi = fromBytes(hash, 8);
        return new Seed128bit(hashLo, hashHi);
    }

    // 对齐原版 Guava Longs.fromBytes(b0..b7) 的大端解码：b0 是最高位字节。
    private static long fromBytes(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    public record Seed128bit(long seedLo, long seedHi) {
        public Seed128bit xor(long lo, long hi) {
            return new Seed128bit(seedLo ^ lo, seedHi ^ hi);
        }

        public Seed128bit xor(Seed128bit other) {
            return xor(other.seedLo, other.seedHi);
        }

        public Seed128bit mixed() {
            return new Seed128bit(mixStafford13(seedLo), mixStafford13(seedHi));
        }
    }
}
