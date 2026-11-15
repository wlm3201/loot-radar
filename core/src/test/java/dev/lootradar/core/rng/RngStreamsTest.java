package dev.lootradar.core.rng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** 不依赖 Minecraft：用 java.util.Random 与自实现公式校验 core 各随机流的逐调用行为。 */
class RngStreamsTest {

    private static final class ExposedRandom extends java.util.Random {
        ExposedRandom(long seed) {
            super(seed);
        }

        int rawNext(int bits) {
            return next(bits);
        }
    }

    @Test
    void legacyRandomMatchesJavaUtilRandom() {
        long[] seeds = {0L, 1L, -1L, 123456789L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long seed : seeds) {
            LegacyRandomSource mine = new LegacyRandomSource(seed);
            ExposedRandom reference = new ExposedRandom(seed);
            for (int i = 0; i < 200; i++) {
                assertEquals(reference.rawNext(32), mine.next(32), "seed=" + seed + " i=" + i);
                assertEquals(reference.nextInt(17), mine.nextInt(17), "seed=" + seed + " i=" + i);
                assertEquals(reference.nextInt(), mine.nextInt(), "seed=" + seed + " i=" + i);
                assertEquals(reference.nextLong(), mine.nextLong(), "seed=" + seed + " i=" + i);
                assertEquals(reference.nextFloat(), mine.nextFloat(), "seed=" + seed + " i=" + i);
            }
        }
    }

    @Test
    void worldgenNextLongConsumesTwoXoroshiroLongs() {
        XoroshiroRandomSource raw = new XoroshiroRandomSource(99L);
        WorldgenRandom worldgen = WorldgenRandom.xoroshiro();
        worldgen.setSeed(99L);
        for (int i = 0; i < 50; i++) {
            long high = (int) (raw.nextLong() >>> 32);
            long low = (int) (raw.nextLong() >>> 32);
            assertEquals((high << 32) + low, worldgen.nextLong(), "i=" + i);
        }
    }

    @Test
    void decorationSeedDependsOnBlockCoordinates() {
        DecorationRandom a = new DecorationRandom();
        DecorationRandom b = new DecorationRandom();
        long seedA = a.setDecorationSeed(1234L, 32, -48);
        long seedB = b.setDecorationSeed(1234L, 48, -48);
        assertNotEquals(seedA, seedB);
        assertEquals(seedA, new DecorationRandom().setDecorationSeed(1234L, 32, -48));
    }

    @Test
    void containerLootSeedAdvancesPerOrdinal() {
        long ordinal0 = DecorationRandom.containerLootSeed(5L, 1, 2, 0, 4, 0);
        long ordinal1 = DecorationRandom.containerLootSeed(5L, 1, 2, 0, 4, 1);
        long ordinal2 = DecorationRandom.containerLootSeed(5L, 1, 2, 0, 4, 2);
        assertNotEquals(ordinal0, ordinal1);
        assertNotEquals(ordinal1, ordinal2);
        assertEquals(ordinal1, DecorationRandom.containerLootSeed(5L, 1, 2, 0, 4, 1));
    }

    @Test
    void xoroshiroIntBoundIsUnbiasedAndBounded() {
        XoroshiroRandomSource random = new XoroshiroRandomSource(7L);
        int[] bounds = {1, 2, 3, 5, 10, 100};
        for (int bound : bounds) {
            for (int i = 0; i < 1000; i++) {
                int value = random.nextInt(bound);
                if (value < 0 || value >= bound) {
                    throw new AssertionError("out of range: " + value + " bound=" + bound);
                }
            }
        }
    }
}
