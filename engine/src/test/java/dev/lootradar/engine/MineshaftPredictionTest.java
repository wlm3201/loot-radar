package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 矿井 Bounce 唱片黄金向量：矿车坐标、lootSeed、硫黄洞穴群系与非目标群系的差异。 */
class MineshaftPredictionTest {
    private static final String TABLE = "minecraft:chests/abandoned_mineshaft";
    private static final String DISC = "minecraft:music_disc_bounce";
    private static final BlockPos BOUNCE = new BlockPos(-1671, -20, -458);
    private static final long BOUNCE_SEED = -8159788421983247496L;
    private static VanillaRuntime runtime;
    private static List<ContainerRecord> sources;

    @BeforeAll
    static void load() {
        runtime = VanillaRuntime.load(0L);
        var start =
                runtime.generateStructure(
                        "minecraft:mineshaft", "overworld", new ChunkPos(-104, -32));
        assertTrue(MinecraftVersionAdapter.isValidStart(start));
        assertTrue(MineshaftBiomes.mayContainBounce(runtime, "overworld", start));
        sources = StructureScanner.scan(runtime, "minecraft:mineshaft", "overworld", start);
    }

    @AfterAll
    static void close() {
        if (runtime != null) runtime.close();
    }

    @Test
    void minecartPositionsAndSeedsMatchIndependentVanillaServer() {
        var truth =
                Map.of(
                        BOUNCE,
                        BOUNCE_SEED,
                        new BlockPos(-1657, -16, -487),
                        4441516085958471279L,
                        new BlockPos(-1657, -16, -492),
                        -1679103913343344057L);
        truth.forEach(
                (pos, seed) -> {
                    var actual =
                            sources.stream()
                                    .filter(c -> c.pos().equals(pos))
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals("minecraft:chest_minecart", actual.blockId());
                    assertEquals(TABLE, actual.lootTable());
                    assertEquals(seed.longValue(), actual.lootSeed(), "seed at " + pos);
                });
    }

    @Test
    void sulfurBiomeAndFirstOpenMatchVanilla() {
        assertTrue(
                runtime.biomeAt("overworld", BOUNCE)
                        .is(Identifier.parse("minecraft:sulfur_caves")));
        var items =
                new VanillaLootOracle(runtime)
                        .roll(TABLE, BOUNCE_SEED, "overworld", Vec3.atCenterOf(BOUNCE));
        assertEquals(
                1,
                items.stream()
                        .filter(i -> i.itemId().equals(DISC))
                        .mapToInt(LootStack::count)
                        .sum());
        Map<String, Integer> expected =
                Map.of(
                        "minecraft:melon_seeds",
                        4,
                        "minecraft:activator_rail",
                        1,
                        "minecraft:name_tag",
                        1,
                        "minecraft:torch",
                        6,
                        DISC,
                        1,
                        "minecraft:glow_berries",
                        3);
        Map<String, Integer> actual = new java.util.HashMap<>();
        items.forEach(i -> actual.merge(i.itemId(), i.count(), Integer::sum));
        assertEquals(expected, actual);
    }

    @Test
    void movingTheSameUnopenedCartOutsideSulfurRemovesBounce() {
        var outside = BlockPos.ZERO;
        assertFalse(
                runtime.biomeAt("overworld", outside)
                        .is(Identifier.parse("minecraft:sulfur_caves")));
        var oracle = new VanillaLootOracle(runtime);
        for (String dimension : List.of("overworld", "the_nether")) {
            assertTrue(
                    oracle.roll(TABLE, BOUNCE_SEED, dimension, Vec3.atCenterOf(outside)).stream()
                            .noneMatch(i -> i.itemId().equals(DISC)));
        }
    }

    @Test
    void featureScratchChunksCannotMutateCachedTerrain() {
        var recording = new RecordingLevel(runtime, "overworld", true);
        var pos = new BlockPos(-1671, -21, -458);
        var chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        var cached = recording.terrainChunk(chunkPos);
        var before = cached.getBlockState(pos);
        var scratch = recording.level().getChunk(chunkPos.x(), chunkPos.z());
        assertNotSame(cached, scratch);
        markPosForPostProcessing(scratch, pos);
        recording
                .level()
                .setBlock(
                        pos,
                        net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(),
                        2);
        assertEquals(before, cached.getBlockState(pos));
        assertTrue(
                recording
                        .level()
                        .getBlockState(pos)
                        .is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK));
    }

    private static void markPosForPostProcessing(Object chunk, BlockPos pos) {
        for (var method : chunk.getClass().getMethods()) {
            if (method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == BlockPos.class
                    && method.getName().toLowerCase().contains("postprocessing")) {
                try {
                    method.invoke(chunk, pos);
                    return;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("no markPosForPostProcessing on " + chunk.getClass());
    }
}
