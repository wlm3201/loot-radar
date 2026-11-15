package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 刷怪房黄金向量：跨装饰区块边界的箱子种子与生物群系 palette 一致性。 */
class DungeonPredictionTest {
    private static VanillaRuntime runtime;

    @BeforeAll
    static void load() {
        runtime = VanillaRuntime.load(0L);
    }

    @AfterAll
    static void close() {
        if (runtime != null) runtime.close();
    }

    @Test
    void upperDungeonCanPlaceChestAcrossItsDecorationChunkBoundary() {
        var sources =
                FeatureScanner.scan(
                        runtime, "minecraft:monster_room", "overworld", new ChunkPos(-95, 13));
        assertChest(sources, new BlockPos(-1523, 10, 213), 9100883092434543185L);
    }

    @Test
    void dungeonCatalogSourceIncludesTheSeparateDeepFeatureStream() {
        var sources =
                FeatureScanner.scan(
                        runtime, "minecraft:monster_room", "overworld", new ChunkPos(-70, 29));
        assertChest(sources, new BlockPos(-1122, -47, 473), 9112813109427294141L);
        assertChest(sources, new BlockPos(-1122, -47, 477), -4088535052735063667L);
    }

    @Test
    void biomeOnlyPalettesMatchTheFullNativeTerrainPipeline() {
        var recording = new RecordingLevel(runtime, "overworld", true);
        for (var pos : List.of(new ChunkPos(-95, 13), new ChunkPos(-70, 29), new ChunkPos(0, 0))) {
            var expected =
                    new java.util.HashSet<
                            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>();
            for (var section : recording.terrainChunk(pos).getSections())
                section.getBiomes().getAll(expected::add);
            assertEquals(expected, recording.biomesInChunk(pos), "Biome union at " + pos);
        }
    }

    private static void assertChest(List<ContainerRecord> sources, BlockPos pos, long seed) {
        var chest =
                sources.stream()
                        .filter(source -> source.pos().equals(pos))
                        .findFirst()
                        .orElseThrow();
        assertEquals("minecraft:chests/simple_dungeon", chest.lootTable());
        assertEquals(seed, chest.lootSeed());
    }
}
