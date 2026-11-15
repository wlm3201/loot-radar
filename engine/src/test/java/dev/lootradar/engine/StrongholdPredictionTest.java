package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 要塞 selective 与 full 扫描必须得到相同的战利品箱集合，验证跳过区块的安全性。 */
class StrongholdPredictionTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String STRUCTURE = "minecraft:stronghold";
    private static final ChunkPos ROOT = new ChunkPos(-218, 190);
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
    void caveAffectedChestSeedMatchesIndependentServer() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if (!net.minecraft.SharedConstants.getCurrentVersion().id().startsWith("26.2")) {
            return;
        }
        var start = runtime.generateStructure(STRUCTURE, DIMENSION, ROOT);
        var sources = StructureScanner.scan(runtime, STRUCTURE, DIMENSION, start);
        var chest =
                sources.stream()
                        .filter(row -> row.pos().equals(new BlockPos(-3473, 15, 3091)))
                        .findFirst()
                        .orElseThrow();
        assertEquals("minecraft:chests/stronghold_corridor", chest.lootTable());
        assertEquals(-7004632495616500103L, chest.lootSeed());
        var items =
                new VanillaLootOracle(runtime)
                        .roll(
                                chest.lootTable(),
                                chest.lootSeed(),
                                DIMENSION,
                                net.minecraft.world.phys.Vec3.atCenterOf(chest.pos()));
        assertEquals(
                Map.of("minecraft:ender_pearl", 1, "minecraft:golden_horse_armor", 1),
                items.stream()
                        .collect(
                                Collectors.toMap(
                                        LootStack::itemId, LootStack::count, Integer::sum)));
    }

    @Test
    void libraryChestPositionsComeFromTheTemplate() {
        var start = runtime.generateStructure(STRUCTURE, DIMENSION, ROOT);
        boolean hasLibrary =
                start.getPieces().stream()
                        .anyMatch(
                                p ->
                                        p
                                                instanceof
                                                net.minecraft.world.level.levelgen.structure
                                                        .structures.StrongholdPieces.Library);
        var sources = StructureScanner.scan(runtime, STRUCTURE, DIMENSION, start);
        var library =
                sources.stream()
                        .filter(
                                row ->
                                        row.lootTable()
                                                .equals("minecraft:chests/stronghold_library"))
                        .toList();
        if (hasLibrary) {
            assertFalse(
                    library.isEmpty(), "a library piece must yield its template-fixed chest(s)");
        }
    }

    @Test
    void selectiveChunksRetainFullFootprintLoot() {
        var selective = runtime.generateStructure(STRUCTURE, DIMENSION, ROOT);
        var full = runtime.generateStructure(STRUCTURE, DIMENSION, ROOT);
        assertTrue(
                StrongholdScanner.lootChunks(runtime, selective).size()
                        < selective.getBoundingBox().intersectingChunks().count());
        assertEquals(
                byPosition(StrongholdScanner.scan(runtime, DIMENSION, full, false)),
                byPosition(StrongholdScanner.scan(runtime, DIMENSION, selective, true)));
    }

    @Test
    void concurrentGenerationRetainsNativePieceLayout() throws Exception {
        var roots = List.of(ROOT, new ChunkPos(-87, 71), new ChunkPos(-13, -106));
        Map<ChunkPos, List<String>> expected =
                roots.stream().collect(Collectors.toMap(p -> p, this::layout));
        try (var workers = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int repetition = 0; repetition < 8; repetition++) {
                for (ChunkPos root : roots)
                    tasks.add(
                            () -> {
                                assertEquals(expected.get(root), layout(root));
                                return null;
                            });
            }
            for (var result : workers.invokeAll(tasks)) result.get();
        }
    }

    private List<String> layout(ChunkPos root) {
        return runtime.generateStructure(STRUCTURE, DIMENSION, root).getPieces().stream()
                .map(piece -> piece.createTag(runtime.structurePieceContext()).toString())
                .toList();
    }

    private static Map<BlockPos, ContainerRecord> byPosition(List<ContainerRecord> rows) {
        return rows.stream().collect(Collectors.toMap(ContainerRecord::pos, row -> row));
    }
}
