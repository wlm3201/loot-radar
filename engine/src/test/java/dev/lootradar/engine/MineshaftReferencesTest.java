package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 校验矿井结构引用顺序与重叠起点下的矿车召回，防止历史误报回归。 */
class MineshaftReferencesTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String MINESHAFT = "minecraft:mineshaft";
    private static VanillaRuntime runtime;

    @BeforeAll
    static void loadRuntime() {
        runtime = VanillaRuntime.load(0L);
    }

    @AfterAll
    static void closeRuntime() {
        if (runtime != null) runtime.close();
    }

    @Test
    void referenceOrderMatchesIndependentVanillaChunkNbt() {
        var expected =
                List.of(
                        new ChunkPos(-280, 40),
                        new ChunkPos(-282, 36),
                        new ChunkPos(-287, 44),
                        new ChunkPos(-284, 39));
        var references = new MineshaftReferences(runtime, DIMENSION);
        assertEquals(expected, references.startsForChunk(MINESHAFT, new ChunkPos(-283, 40)));
    }

    @Test
    void overlappingStartsRecoverRealCartsAndRemoveFormerFalsePositives() {
        var root = new ChunkPos(-284, 39);
        var start = runtime.generateStructure(MINESHAFT, DIMENSION, root);
        assertTrue(MinecraftVersionAdapter.isValidStart(start));
        List<ContainerRecord> carts = StructureScanner.scan(runtime, MINESHAFT, DIMENSION, start);

        Map<BlockPos, Long> actual =
                Map.of(
                        new BlockPos(-4518, 20, 630), -7397978527538741916L,
                        new BlockPos(-4488, 29, 637), -4616503504576048849L,
                        new BlockPos(-4488, 25, 662), -4080125667276959815L);
        for (var entry : actual.entrySet()) {
            var matches = carts.stream().filter(cart -> cart.pos().equals(entry.getKey())).toList();
            assertEquals(1, matches.size(), "Expected one actual minecart at " + entry.getKey());
            assertEquals("minecraft:chests/abandoned_mineshaft", matches.getFirst().lootTable());
            assertEquals(
                    entry.getValue().longValue(),
                    matches.getFirst().lootSeed(),
                    "Vanilla loot seed at " + entry.getKey());
        }

        Set<BlockPos> formerFalsePositives =
                Set.of(
                        new BlockPos(-4533, 22, 650),
                        new BlockPos(-4521, 19, 648),
                        new BlockPos(-4521, 20, 628));
        for (var cart : carts) {
            assertFalse(
                    formerFalsePositives.contains(cart.pos()),
                    "Former false positive returned at " + cart.pos());
        }
    }
}
