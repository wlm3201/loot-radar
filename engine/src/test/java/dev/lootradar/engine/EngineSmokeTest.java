package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.structure.PlacementSpec;
import dev.lootradar.core.structure.RandomSpreadLocator;
import dev.lootradar.core.structure.StructureSetSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 端到端冒烟：林地府邸固定起点的 7 个箱子、沙漠神殿抽奖可重复、装饰下标非负。 */
class EngineSmokeTest {

    private static VanillaRuntime runtime;
    private static VanillaLootOracle oracle;

    @BeforeAll
    static void setUp() {
        runtime = VanillaRuntime.load(0L);
        oracle = new VanillaLootOracle(runtime);
    }

    @AfterAll
    static void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void seedZeroMansionStartHasSevenLootChests() {
        StructureStart start =
                runtime.generateStructure(
                        "minecraft:mansion", "minecraft:overworld", new ChunkPos(-221, -52));
        assertTrue(
                MinecraftVersionAdapter.isValidStart(start),
                "seed 0 mansion at (-221,-52) must generate");

        List<ContainerRecord> sources =
                StructureScanner.scan(runtime, "minecraft:mansion", "minecraft:overworld", start);
        List<ContainerRecord> chests =
                sources.stream().filter(ContainerRecord::hasLootTable).toList();
        assertEquals(7, chests.size(), "theory.md golden: 7 loot chests, got " + chests);
        for (ContainerRecord chest : chests) {
            assertEquals("minecraft:chests/woodland_mansion", chest.lootTable());
            assertTrue(chest.lootSeed() != 0L, "vanilla writes non-zero loot seeds");
        }
    }

    @Test
    void desertPyramidChestsReplayDeterministically() {
        StructureSetSpec set =
                new StructureSetSpec(
                        "minecraft:desert_pyramids",
                        new PlacementSpec(
                                PlacementSpec.Type.RANDOM_SPREAD,
                                "linear",
                                14357617,
                                32,
                                8,
                                1.0f,
                                "none",
                                null,
                                null,
                                null,
                                null,
                                null),
                        List.of(new StructureSetSpec.Member("minecraft:desert_pyramid", 1)));
        Map<String, StructureSetSpec> allSets = new HashMap<>();

        StructureStart pyramidStart = MinecraftVersionAdapter.invalidStart();
        for (RandomSpreadLocator.Candidate candidate :
                RandomSpreadLocator.candidatesInRadius(set, 0L, 0, 0, 400, allSets)) {
            StructureStart start =
                    runtime.generateStructure(
                            "minecraft:desert_pyramid",
                            "minecraft:overworld",
                            new ChunkPos(candidate.chunkX(), candidate.chunkZ()));
            if (MinecraftVersionAdapter.isValidStart(start)) {
                pyramidStart = start;
                break;
            }
        }
        assertTrue(
                MinecraftVersionAdapter.isValidStart(pyramidStart),
                "expected a desert pyramid within 400 chunks of origin at seed 0");

        List<ContainerRecord> sources =
                StructureScanner.scan(
                        runtime, "minecraft:desert_pyramid", "minecraft:overworld", pyramidStart);
        List<ContainerRecord> chests =
                sources.stream()
                        .filter(c -> c.lootTable().equals("minecraft:chests/desert_pyramid"))
                        .toList();
        assertTrue(chests.size() >= 4, "pyramid should expose its chests, got " + chests.size());

        for (ContainerRecord chest : chests) {
            List<LootStack> first = oracle.roll(chest.lootTable(), chest.lootSeed());
            List<LootStack> second = oracle.roll(chest.lootTable(), chest.lootSeed());
            assertEquals(first, second, "loot replay must be deterministic");
        }
    }

    @Test
    void decorationCoordinatesAreStable() {
        var mansion = runtime.structureDecorationCoordinates("minecraft:mansion");
        var pyramid = runtime.structureDecorationCoordinates("minecraft:desert_pyramid");
        assertTrue(mansion.step() >= 0 && mansion.indexWithinStep() >= 0);
        assertTrue(pyramid.step() >= 0 && pyramid.indexWithinStep() >= 0);
    }
}
