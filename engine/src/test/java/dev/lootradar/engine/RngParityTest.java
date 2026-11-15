package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.lootradar.core.rng.LegacyRandomSource;
import dev.lootradar.core.rng.WorldgenRandom;
import dev.lootradar.core.rng.XoroshiroRandomSource;
import dev.lootradar.core.structure.PlacementSpec;
import dev.lootradar.core.structure.RandomSpreadLocator;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 把 core 的 RNG、装饰流、random_spread 候选与频率 reducer 逐一与原生类对照。 */
class RngParityTest {

    private static final long[] SEEDS = {
        0L, 1L, -1L, 42L, 123456789L, Long.MIN_VALUE, Long.MAX_VALUE
    };

    @BeforeAll
    static void bootstrapVanilla() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void legacyRandomSourceMatchesVanilla() {
        for (long seed : SEEDS) {
            LegacyRandomSource mine = new LegacyRandomSource(seed);
            net.minecraft.world.level.levelgen.LegacyRandomSource vanilla =
                    new net.minecraft.world.level.levelgen.LegacyRandomSource(seed);
            for (int i = 0; i < 100; i++) {
                assertEquals(vanilla.next(31), mine.next(31), "next(31) seed=" + seed + " i=" + i);
                assertEquals(vanilla.next(32), mine.next(32), "next(32) seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextLong(), mine.nextLong(), "nextLong seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextInt(97), mine.nextInt(97), "nextInt seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextFloat(),
                        mine.nextFloat(),
                        "nextFloat seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextDouble(),
                        mine.nextDouble(),
                        "nextDouble seed=" + seed + " i=" + i);
            }
        }
    }

    @Test
    void xoroshiroRandomSourceMatchesVanilla() {
        for (long seed : SEEDS) {
            XoroshiroRandomSource mine = new XoroshiroRandomSource(seed);
            net.minecraft.world.level.levelgen.XoroshiroRandomSource vanilla =
                    new net.minecraft.world.level.levelgen.XoroshiroRandomSource(seed);
            for (int i = 0; i < 100; i++) {
                assertEquals(
                        vanilla.nextLong(), mine.nextLong(), "nextLong seed=" + seed + " i=" + i);
                assertEquals(vanilla.nextInt(), mine.nextInt(), "nextInt seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextInt(100),
                        mine.nextInt(100),
                        "nextInt(100) seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextInt(3), mine.nextInt(3), "nextInt(3) seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextFloat(),
                        mine.nextFloat(),
                        "nextFloat seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextDouble(),
                        mine.nextDouble(),
                        "nextDouble seed=" + seed + " i=" + i);
            }
            mine.setSeed(777L);
            vanilla.setSeed(777L);
            for (int i = 0; i < 20; i++) {
                assertEquals(vanilla.nextLong(), mine.nextLong(), "after setSeed i=" + i);
            }
        }
    }

    @Test
    void worldgenRandomStreamsMatchVanilla() {
        for (long seed : SEEDS) {
            WorldgenRandom mine = WorldgenRandom.xoroshiro();
            net.minecraft.world.level.levelgen.WorldgenRandom vanilla =
                    new net.minecraft.world.level.levelgen.WorldgenRandom(
                            new net.minecraft.world.level.levelgen.XoroshiroRandomSource(0L));
            long mineDecoration = mine.setDecorationSeed(seed, 320, -480);
            long vanillaDecoration = vanilla.setDecorationSeed(seed, 320, -480);
            assertEquals(vanillaDecoration, mineDecoration, "decoration seed=" + seed);
            for (int i = 0; i < 50; i++) {
                assertEquals(
                        vanilla.nextLong(),
                        mine.nextLong(),
                        "decoration nextLong seed=" + seed + " i=" + i);
                assertEquals(
                        vanilla.nextInt(7),
                        mine.nextInt(7),
                        "decoration nextInt seed=" + seed + " i=" + i);
            }

            WorldgenRandom mineLegacy = WorldgenRandom.legacy();
            net.minecraft.world.level.levelgen.WorldgenRandom vanillaLegacy =
                    new net.minecraft.world.level.levelgen.WorldgenRandom(
                            new net.minecraft.world.level.levelgen.LegacyRandomSource(0L));
            mineLegacy.setLargeFeatureWithSalt(seed, 12, -34, 14357617);
            vanillaLegacy.setLargeFeatureWithSalt(seed, 12, -34, 14357617);
            for (int i = 0; i < 50; i++) {
                assertEquals(
                        vanillaLegacy.nextInt(24),
                        mineLegacy.nextInt(24),
                        "salt nextInt seed=" + seed + " i=" + i);
            }

            mineLegacy.setLargeFeatureSeed(seed, -5, 9);
            vanillaLegacy.setLargeFeatureSeed(seed, -5, 9);
            for (int i = 0; i < 50; i++) {
                assertEquals(
                        vanillaLegacy.nextDouble(),
                        mineLegacy.nextDouble(),
                        "largeFeature nextDouble seed=" + seed + " i=" + i);
            }
        }
    }

    @Test
    void randomSpreadCandidatesMatchVanilla() {
        record Case(
                int spacing,
                int separation,
                net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType type,
                int salt,
                String spreadName) {}
        List<Case> cases =
                List.of(
                        new Case(
                                32,
                                8,
                                net.minecraft.world.level.levelgen.structure.placement
                                        .RandomSpreadType.LINEAR,
                                14357617,
                                "linear"),
                        new Case(
                                80,
                                20,
                                net.minecraft.world.level.levelgen.structure.placement
                                        .RandomSpreadType.TRIANGULAR,
                                10387319,
                                "triangular"),
                        new Case(
                                34,
                                12,
                                net.minecraft.world.level.levelgen.structure.placement
                                        .RandomSpreadType.LINEAR,
                                94251327,
                                "linear"),
                        new Case(
                                24,
                                8,
                                net.minecraft.world.level.levelgen.structure.placement
                                        .RandomSpreadType.LINEAR,
                                20083232,
                                "linear"));
        for (Case c : cases) {
            PlacementSpec spec =
                    new PlacementSpec(
                            PlacementSpec.Type.RANDOM_SPREAD,
                            c.spreadName(),
                            c.salt(),
                            c.spacing(),
                            c.separation(),
                            1.0f,
                            "none",
                            null,
                            null,
                            null,
                            null,
                            null);
            var vanillaPlacement =
                    new net.minecraft.world.level.levelgen.structure.placement
                            .RandomSpreadStructurePlacement(
                            c.spacing(), c.separation(), c.type(), c.salt());
            for (long seed : SEEDS) {
                for (int chunkX = -40; chunkX <= 40; chunkX += 7) {
                    for (int chunkZ = -40; chunkZ <= 40; chunkZ += 11) {
                        var vanillaPos =
                                vanillaPlacement.getPotentialStructureChunk(seed, chunkX, chunkZ);
                        var minePos =
                                RandomSpreadLocator.potentialChunkForChunk(
                                        spec, seed, chunkX, chunkZ);
                        assertEquals(
                                vanillaPos.x(),
                                minePos.chunkX(),
                                "chunkX seed="
                                        + seed
                                        + " at "
                                        + chunkX
                                        + ","
                                        + chunkZ
                                        + " case="
                                        + c);
                        assertEquals(
                                vanillaPos.z(),
                                minePos.chunkZ(),
                                "chunkZ seed="
                                        + seed
                                        + " at "
                                        + chunkX
                                        + ","
                                        + chunkZ
                                        + " case="
                                        + c);
                    }
                }
            }
        }
    }

    @Test
    void frequencyReducersMatchVanilla() {
        record Case(int spacing, int separation, float frequency, int salt, String method) {}
        List<Case> cases =
                List.of(
                        new Case(32, 8, 0.2f, 165745296, "legacy_type_1"),
                        new Case(1, 0, 0.01f, 0, "legacy_type_2"),
                        new Case(1, 0, 0.004f, 0, "legacy_type_3"),
                        new Case(32, 8, 0.5f, 14357617, "default"));
        for (Case c : cases) {
            PlacementSpec spec =
                    new PlacementSpec(
                            PlacementSpec.Type.RANDOM_SPREAD,
                            "linear",
                            c.salt(),
                            c.spacing(),
                            c.separation(),
                            c.frequency(),
                            c.method(),
                            null,
                            null,
                            null,
                            null,
                            null);
            for (long seed : SEEDS) {
                for (int chunkX = -30; chunkX <= 30; chunkX += 3) {
                    for (int chunkZ = -30; chunkZ <= 30; chunkZ += 5) {
                        boolean vanilla =
                                MinecraftVersionAdapter.vanillaFrequencyAllows(
                                        c.method(), seed, c.salt(), chunkX, chunkZ, c.frequency());
                        boolean mine =
                                RandomSpreadLocator.frequencyAllows(spec, seed, chunkX, chunkZ);
                        assertEquals(
                                vanilla,
                                mine,
                                "frequency seed="
                                        + seed
                                        + " at "
                                        + chunkX
                                        + ","
                                        + chunkZ
                                        + " case="
                                        + c);
                    }
                }
            }
        }
    }
}
