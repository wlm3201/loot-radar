package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 沙漠水井 placed feature：按 1/1000 rarity 与 desert 群系过滤后必须扫到考古容器。 */
class DesertWellPredictionTest {

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
    void desertWellArchaeologyIsCaptured() {
        int found = 0;
        for (ChunkPos well : desertWellCandidates(5000)) {
            for (ContainerRecord source :
                    FeatureScanner.scan(
                            runtime, "minecraft:desert_well", "minecraft:overworld", well)) {
                if (source.kind() == ContainerKind.ARCHAEOLOGY
                        && source.lootTable().contains("desert_well")) {
                    found++;
                }
            }
        }
        assertTrue(
                found >= 1,
                "desert well suspicious sand must be captured as archaeology at least once, got "
                        + found);
    }

    private static java.util.List<ChunkPos> desertWellCandidates(int radius) {
        int bound = (radius + 31) / 16;
        java.util.List<ChunkPos> result = new java.util.ArrayList<>();
        for (int x = -bound; x <= bound; x++) {
            for (int z = -bound; z <= bound; z++) {
                ChunkPos p = new ChunkPos(x, z);
                long bx = p.getMinBlockX() + 8L, bz = p.getMinBlockZ() + 8L;
                if (bx * bx + bz * bz > (long) (radius + 32) * (radius + 32)) continue;
                if (!FeatureScanner.rarityAllows(
                        runtime, "minecraft:desert_well", "minecraft:overworld", p, 1000)) continue;
                if (!runtime.biomeAt(
                                "minecraft:overworld",
                                new BlockPos(p.getMinBlockX() + 8, 64, p.getMinBlockZ() + 8))
                        .is(net.minecraft.world.level.biome.Biomes.DESERT)) continue;
                result.add(p);
            }
        }
        return result;
    }
}
