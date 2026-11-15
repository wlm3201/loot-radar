package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** 雪屋地下室箱子回归；26.4 额外统计 igloo_chest 产出 Flurry 唱片的频率区间。 */
class IglooRegressionTest {

    @Test
    void iglooBasementChestPredicted() {
        try (VanillaRuntime runtime = VanillaRuntime.load(12345L)) {
            var version = GameVersion.parse(SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var set = registry.sets().get("minecraft:igloos");
            var members =
                    set.structures().stream()
                            .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                            .toList();
            var start =
                    runtime.generateSelectedStructure(
                            members, "minecraft:overworld", new ChunkPos(171, 6));
            assertTrue(
                    MinecraftVersionAdapter.isValidStart(start),
                    "golden vector drifted: igloo start (171,6)");
            String id =
                    runtime.registries()
                            .lookupOrThrow(Registries.STRUCTURE)
                            .getKey(start.getStructure())
                            .toString();
            assertEquals("minecraft:igloo", id);
            List<ContainerRecord> records =
                    StructureScanner.scan(runtime, id, "minecraft:overworld", start);
            ContainerRecord chest =
                    records.stream()
                            .filter(record -> record.lootTable().endsWith("chests/igloo_chest"))
                            .findFirst()
                            .orElse(null);
            assertNotNull(
                    chest,
                    "basement chest (chests/igloo_chest) must be predicted, got "
                            + records.stream()
                                    .map(record -> record.lootTable() + "@" + record.pos())
                                    .toList());
            assertTrue(chest.lootSeed() != 0L, "basement chest must carry a real loot seed");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void flurryDiscRollsFromIglooTableOn264() {
        String gameId = SharedConstants.getCurrentVersion().id();
        if (!gameId.startsWith("26.4")) {
            return;
        }
        try (VanillaRuntime runtime = VanillaRuntime.load(12345L)) {
            var oracle = new VanillaLootOracle(runtime);
            int flurry = 0;
            int trials = 400;
            for (long seed = 1; seed <= trials; seed++) {
                for (var stack : oracle.roll("minecraft:chests/igloo_chest", seed)) {
                    if (stack.itemId().equals("minecraft:music_disc_flurry")) {
                        flurry++;
                        break;
                    }
                }
            }
            assertTrue(
                    flurry >= 60 && flurry <= 200,
                    "flurry rate out of band: " + flurry + "/" + trials);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
