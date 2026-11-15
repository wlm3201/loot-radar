package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** 26.2 沙漠神殿黄金向量：两组起点的箱子与可疑沙子坐标必须被精确预测。 */
class DesertPyramidRegressionTest {

    private static final List<BlockPos> TRUTH_CHESTS =
            List.of(
                    new BlockPos(8, 58, -2998),
                    new BlockPos(10, 58, -2996),
                    new BlockPos(12, 58, -2998),
                    new BlockPos(1306, 51, -4056),
                    new BlockPos(1308, 51, -4054));

    private static final List<BlockPos> TRUTH_SUSPICIOUS =
            List.of(
                    new BlockPos(15, 66, -3002),
                    new BlockPos(15, 68, -3003),
                    new BlockPos(17, 68, -3002),
                    new BlockPos(17, 68, -2999),
                    new BlockPos(18, 68, -3001),
                    new BlockPos(1311, 60, -4048),
                    new BlockPos(1311, 61, -4048));

    @Test
    void desertPyramidContainersMatchTruthExactly() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if (!net.minecraft.SharedConstants.getCurrentVersion().id().startsWith("26.2")) {
            return;
        }
        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            var version = GameVersion.parse(SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var set = registry.sets().get("minecraft:desert_pyramids");
            var members =
                    set.structures().stream()
                            .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                            .toList();
            List<ContainerRecord> records = new ArrayList<>();
            for (ChunkPos startChunk : List.of(new ChunkPos(0, -188), new ChunkPos(81, -254))) {
                var start =
                        runtime.generateSelectedStructure(
                                members, "minecraft:overworld", startChunk);
                assertTrue(
                        MinecraftVersionAdapter.isValidStart(start),
                        "test vector drifted: pyramid start " + startChunk);
                String id =
                        runtime.registries()
                                .lookupOrThrow(Registries.STRUCTURE)
                                .getKey(start.getStructure())
                                .toString();
                records.addAll(StructureScanner.scan(runtime, id, "minecraft:overworld", start));
            }
            Set<BlockPos> predicted = new java.util.HashSet<>();
            for (var record : records) predicted.add(record.pos());
            for (BlockPos pos : TRUTH_CHESTS) {
                assertTrue(predicted.contains(pos), "chest " + pos + " must be predicted exactly");
            }
            for (BlockPos pos : TRUTH_SUSPICIOUS) {
                assertTrue(
                        predicted.contains(pos),
                        "suspicious sand " + pos + " must be predicted exactly");
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
