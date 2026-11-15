package dev.lootradar.core.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** random_spread 候选与频率缩减的性质测试：网格边界、确定性、自检一致性与稀释效果。 */
class RandomSpreadLocatorTest {

    private static final Map<String, StructureSetSpec> NO_EXCLUSION = Map.of();

    private static StructureSetSpec set(String spreadType, int salt, int spacing, int separation) {
        return new StructureSetSpec(
                "test:set",
                new PlacementSpec(
                        PlacementSpec.Type.RANDOM_SPREAD,
                        spreadType,
                        salt,
                        spacing,
                        separation,
                        1.0f,
                        "none",
                        null,
                        null,
                        null,
                        null,
                        null),
                List.of(new StructureSetSpec.Member("test:structure", 1)));
    }

    @Test
    void candidateStaysInsideItsGridCell() {
        StructureSetSpec set = set("linear", 14357617, 32, 8);
        for (long seed : new long[] {0L, 1L, -1L, 123456789L}) {
            for (int gridX = -20; gridX <= 20; gridX++) {
                for (int gridZ = -20; gridZ <= 20; gridZ++) {
                    RandomSpreadLocator.Candidate candidate =
                            RandomSpreadLocator.potentialChunk(set.placement(), seed, gridX, gridZ);
                    int cellMinX = gridX * 32;
                    int cellMinZ = gridZ * 32;
                    assertTrue(
                            candidate.chunkX() >= cellMinX && candidate.chunkX() < cellMinX + 32,
                            "x out of cell");
                    assertTrue(
                            candidate.chunkZ() >= cellMinZ && candidate.chunkZ() < cellMinZ + 32,
                            "z out of cell");
                }
            }
        }
    }

    @Test
    void potentialChunkIsDeterministic() {
        StructureSetSpec set = set("triangular", 10387319, 80, 20);
        var first = RandomSpreadLocator.potentialChunk(set.placement(), 0L, 3, -7);
        var second = RandomSpreadLocator.potentialChunk(set.placement(), 0L, 3, -7);
        assertEquals(first, second);
    }

    @Test
    void enumerationReturnsOnlyOwnCandidates() {
        StructureSetSpec set = set("linear", 94251327, 34, 12);
        int centerChunkX = 5;
        int centerChunkZ = -3;
        int radiusChunks = 68;
        List<RandomSpreadLocator.Candidate> candidates =
                RandomSpreadLocator.candidatesInRadius(
                        set, 42L, centerChunkX, centerChunkZ, radiusChunks, NO_EXCLUSION);
        for (RandomSpreadLocator.Candidate candidate : candidates) {
            assertTrue(Math.abs(candidate.chunkX() - centerChunkX) <= radiusChunks);
            assertTrue(Math.abs(candidate.chunkZ() - centerChunkZ) <= radiusChunks);
            assertTrue(
                    RandomSpreadLocator.isStructureChunk(
                            set, 42L, candidate.chunkX(), candidate.chunkZ(), NO_EXCLUSION));
        }
        assertTrue(!candidates.isEmpty(), "expected at least one candidate in 68-chunk radius");
    }

    @Test
    void frequencyFilterIsStableForLegacyTypes() {
        StructureSetSpec outpost =
                new StructureSetSpec(
                        "test:outpost",
                        new PlacementSpec(
                                PlacementSpec.Type.RANDOM_SPREAD,
                                "linear",
                                165745296,
                                32,
                                8,
                                0.2f,
                                "legacy_type_1",
                                null,
                                null,
                                null,
                                null,
                                null),
                        List.of(new StructureSetSpec.Member("test:outpost", 1)));
        int allowed = 0;
        for (int x = 0; x < 256; x++) {
            if (RandomSpreadLocator.frequencyAllows(outpost.placement(), 7L, x, x * 3 + 1)) {
                allowed++;
            }
        }
        assertTrue(
                allowed > 0 && allowed < 256,
                "legacy_type_1 should thin candidates, got " + allowed);
    }
}
