package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** 跨版本战利品表覆盖的 codec 解析校验，以及 26.4 雪屋箱产出 Flurry 唱片的黄金向量。 */
class LootTableOverrideTest {

    private static final long SEED = 4217525504911621938L;
    private static final BlockPos TARGET = new BlockPos(3250, 36, 5367);

    private static final long CHEST_SEED = -8034585109602627041L;

    static Map<String, String> loadOverrides(String versionId) throws Exception {
        Path dir = VersionRegistry.resolveDataRoot().resolve(versionId).resolve("loot_tables");
        Map<String, String> map = new LinkedHashMap<>();
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path path : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                    String rel = dir.relativize(path).toString().replace('\\', '/');
                    map.put(
                            "minecraft:" + rel.substring(0, rel.length() - 5),
                            Files.readString(path));
                }
            }
        }
        return map;
    }

    @Test
    void allVersionOverridesParseOnTheRuntimeCodec() {
        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            for (String versionId : List.of("26.1.2", "26.3", "26.4")) {
                try {
                    Map<String, String> overrides = loadOverrides(versionId);
                    assertTrue(overrides.size() >= 2, versionId + " overrides expected");
                    assertTrue(
                            overrides.containsKey("minecraft:entities/elder_guardian"),
                            versionId + " elder-guardian table is the single entity source");
                    assertTrue(
                            overrides.keySet().stream()
                                    .noneMatch(
                                            id ->
                                                    id.startsWith("minecraft:entities/")
                                                            && !id.equals(
                                                                    "minecraft:entities/elder_guardian")),
                            versionId + " must not carry unrelated mob drop tables");
                    assertTrue(
                            overrides.keySet().stream()
                                    .noneMatch(id -> id.startsWith("minecraft:equipment/")),
                            versionId + " must not carry equipment tables (never rolled)");
                    assertTrue(
                            overrides.keySet().stream()
                                    .noneMatch(
                                            id ->
                                                    id.startsWith("minecraft:gameplay/")
                                                            && !id.equals(
                                                                    "minecraft:gameplay/fishing/fish")),
                            versionId + " must not carry unrelated gameplay tables");
                    var oracle = new VanillaLootOracle(runtime, overrides);
                    for (String id : overrides.keySet()) {
                        assertNotNull(oracle.table(id), versionId + " " + id + " must parse");
                    }
                    assertEquals(
                            0,
                            oracle.overrideParseFailures(),
                            versionId
                                    + " overrides must all parse without falling back: "
                                    + oracle.overrideParseFailureLog());
                } catch (Exception e) {
                    throw new IllegalStateException(versionId + " overrides failed", e);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void userCaseChestPredictedExactlyAndRollsFlurry() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        try (VanillaRuntime runtime = VanillaRuntime.load(SEED)) {
            var registry =
                    VersionRegistry.load(GameVersion.V26_4, VersionRegistry.resolveDataRoot());
            var set = registry.sets().get("minecraft:igloos");
            var members =
                    set.structures().stream()
                            .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                            .toList();
            ContainerRecord chest = null;
            int cx = TARGET.getX() >> 4;
            int cz = TARGET.getZ() >> 4;
            for (int x = cx - 8; x <= cx + 8 && chest == null; x++) {
                for (int z = cz - 8; z <= cz + 8 && chest == null; z++) {
                    var start =
                            runtime.generateSelectedStructure(
                                    members, "minecraft:overworld", new ChunkPos(x, z));
                    if (start == null || !MinecraftVersionAdapter.isValidStart(start)) continue;
                    String id =
                            runtime.registries()
                                    .lookupOrThrow(
                                            net.minecraft.core.registries.Registries.STRUCTURE)
                                    .getKey(start.getStructure())
                                    .toString();
                    if (!id.equals("minecraft:igloo")) continue;
                    for (var record :
                            StructureScanner.scan(
                                    runtime, id, "minecraft:overworld", start, null, registry)) {
                        if (record.pos().equals(TARGET)) {
                            chest = record;
                            break;
                        }
                    }
                }
            }
            assertNotNull(chest, "user-reported chest at " + TARGET + " must be predicted");
            assertEquals("minecraft:chests/igloo_chest", chest.lootTable());
            assertEquals(CHEST_SEED, chest.lootSeed(), "chest loot seed must match 26.4 truth");

            var oracle = new VanillaLootOracle(runtime, loadOverrides("26.4"));
            List<LootStack> items = oracle.roll(chest.lootTable(), chest.lootSeed());
            assertTrue(
                    items.stream().anyMatch(s -> s.itemId().equals("minecraft:music_disc_flurry")),
                    "26.4 table must yield the Flurry disc at this chest (user truth), got "
                            + items);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
