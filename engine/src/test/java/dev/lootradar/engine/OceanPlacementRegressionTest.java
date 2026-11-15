package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.structure.RandomSpreadLocator;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** 沉船与海底废墟的容器坐标真值回归，覆盖高度敏感结构的定位与放置。 */
class OceanPlacementRegressionTest {

    private record Truth(String setId, BlockPos pos, String hint) {}

    private static final List<Truth> TRUTH =
            List.of(
                    new Truth("minecraft:shipwrecks", new BlockPos(-3534, 50, 210), "沉船宝箱"),
                    new Truth("minecraft:shipwrecks", new BlockPos(-3333, 52, -3713), "沉船补给箱"),
                    new Truth("minecraft:shipwrecks", new BlockPos(-3754, 51, -616), "沉船地图箱"),
                    new Truth("minecraft:ocean_ruins", new BlockPos(-3194, 8, 809), "大型海底废墟"),
                    new Truth("minecraft:ocean_ruins", new BlockPos(-4804, 48, -908), "小型海底废墟"));

    @Test
    void oceanContainersMatchTruthExactly() {
        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            var version = GameVersion.parse(SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            for (Truth truth : TRUTH) {
                var set = registry.sets().get(truth.setId());
                var members =
                        set.structures().stream()
                                .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                                .toList();
                boolean found = false;
                var candidates =
                        RandomSpreadLocator.candidatesInRadius(
                                set,
                                0L,
                                truth.pos().getX() >> 4,
                                truth.pos().getZ() >> 4,
                                8,
                                registry.sets());
                for (var p : candidates) {
                    var start =
                            runtime.generateSelectedStructure(
                                    members,
                                    "minecraft:overworld",
                                    new ChunkPos(p.chunkX(), p.chunkZ()));
                    if (!MinecraftVersionAdapter.isValidStart(start)) continue;
                    var box = start.getBoundingBox();
                    boolean coversXZ =
                            truth.pos().getX() >= box.minX()
                                    && truth.pos().getX() <= box.maxX()
                                    && truth.pos().getZ() >= box.minZ()
                                    && truth.pos().getZ() <= box.maxZ();
                    if (!coversXZ) continue;
                    String id =
                            runtime.registries()
                                    .lookupOrThrow(Registries.STRUCTURE)
                                    .getKey(start.getStructure())
                                    .toString();
                    var records = StructureScanner.scan(runtime, id, "minecraft:overworld", start);
                    boolean hit = records.stream().anyMatch(r -> r.pos().equals(truth.pos()));
                    assertTrue(
                            hit,
                            truth.hint()
                                    + " container "
                                    + truth.pos()
                                    + " must be predicted exactly (structure "
                                    + id
                                    + ", got "
                                    + records.stream()
                                            .map(r -> r.pos() + "/" + r.lootTable())
                                            .toList()
                                    + ")");
                    found = true;
                    break;
                }
                assertTrue(
                        found, truth.hint() + ": no valid structure start covers " + truth.pos());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
