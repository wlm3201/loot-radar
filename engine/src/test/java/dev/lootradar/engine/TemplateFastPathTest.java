package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 沉船快速路径与常规逐区块放置重放必须给出一致的容器坐标与 lootSeed。 */
class TemplateFastPathTest {
    private static VanillaRuntime runtime;

    @BeforeAll
    static void load() {
        runtime = VanillaRuntime.load(0L);
    }

    @AfterAll
    static void close() {
        if (runtime != null) runtime.close();
    }

    private static List<StructureStart> shipwreckStarts(int limit) {
        List<StructureStart> starts = new ArrayList<>();
        for (int cx = -64; cx <= 0 && starts.size() < limit; cx += 4) {
            for (int cz = 0; cz <= 64 && starts.size() < limit; cz += 4) {
                StructureStart start =
                        runtime.generateStructure(
                                "minecraft:shipwreck", "minecraft:overworld", new ChunkPos(cx, cz));
                if (!start.getPieces().isEmpty()) starts.add(start);
            }
        }
        return starts;
    }

    private static Map<String, String> byKey(List<ContainerRecord> records) {
        Map<String, String> map = new TreeMap<>();
        for (ContainerRecord record : records) {
            map.put(
                    record.pos().getX() + "," + record.pos().getY() + "," + record.pos().getZ(),
                    record.lootTable() + "@" + record.lootSeed());
        }
        return map;
    }

    @Test
    void fastPathMatchesTheFullReplay() {
        List<StructureStart> starts = shipwreckStarts(6);
        assertTrue(starts.size() >= 3, "expected several shipwreck starts near origin");
        long fastNanos = 0;
        long replayNanos = 0;
        int containers = 0;
        for (StructureStart start : starts) {
            long t0 = System.nanoTime();
            List<ContainerRecord> fast =
                    TemplateFastPath.scan(
                            runtime, "minecraft:overworld", "minecraft:shipwreck", start);
            fastNanos += System.nanoTime() - t0;
            assertNotNull(fast, "ocean shipwrecks must be in the fast subset");
            t0 = System.nanoTime();
            List<ContainerRecord> replay =
                    StructureScanner.scan(
                            runtime, "minecraft:shipwreck", "minecraft:overworld", start);
            replayNanos += System.nanoTime() - t0;
            containers += replay.size();
            assertEquals(
                    byKey(replay),
                    byKey(fast),
                    "fast path must match the replay exactly for start " + start.getChunkPos());
        }
        System.err.println(
                "parity profile starts="
                        + starts.size()
                        + " containers="
                        + containers
                        + " fast="
                        + fastNanos / 1_000_000
                        + "ms replay="
                        + replayNanos / 1_000_000
                        + "ms");
    }

    @Test
    void beachedIsInTheFastSubset() {
        assertNull(
                TemplateFastPath.scan(
                        runtime, "minecraft:overworld", "minecraft:shipwreck_beached", null),
                "an invalid start still falls back");
        assertTrue(TemplateFastPath.supports("minecraft:shipwreck_beached"));
    }
}
