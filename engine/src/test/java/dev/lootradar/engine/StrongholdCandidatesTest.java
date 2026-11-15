package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.junit.jupiter.api.Test;

/** 诊断 StrongholdSeedCandidates 的候选种子枚举是否覆盖真实箱子种子。 */
class StrongholdCandidatesTest {

    private static final BlockPos TARGET = new BlockPos(-150, -36, -1808);
    private static final long TRUE_SEED = 3495995108324182680L;

    @Test
    void enumeratesTrueSeedForModelSensitiveChest() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if (!net.minecraft.SharedConstants.getCurrentVersion().id().startsWith("26.2")) {
            return;
        }
        try (VanillaRuntime runtime = VanillaRuntime.load(1L)) {
            StructureStart start = findStronghold(runtime, TARGET);
            assertNotNull(start, "stronghold containing " + TARGET + " must be found");

            var candidates =
                    StrongholdSeedCandidates.enumerate(
                            runtime, "minecraft:overworld", start, TARGET);

            List<ContainerRecord> records =
                    StrongholdScanner.scan(runtime, "minecraft:overworld", start);
            ContainerRecord target =
                    records.stream()
                            .filter(record -> record.pos().equals(TARGET))
                            .findFirst()
                            .orElse(null);
            assertNotNull(target, "target chest must be predicted by the baseline scan");
            assertEquals("minecraft:chests/stronghold_corridor", target.lootTable());

            assertTrue(
                    candidates.frequencies().containsKey(TRUE_SEED),
                    "true seed "
                            + TRUE_SEED
                            + " must be among candidates: scanSeed="
                            + target.lootSeed()
                            + " capturedIndex="
                            + candidates.capturedIndex()
                            + " window="
                            + candidates.window()
                            + " streamLength="
                            + candidates.streamLength()
                            + " freqSize="
                            + candidates.frequencies().size()
                            + " trueIndex="
                            + candidates.outputs().indexOf(TRUE_SEED)
                            + " trueIndexOffset="
                            + (candidates.outputs().indexOf(TRUE_SEED) - candidates.capturedIndex())
                            + " freq="
                            + candidates.frequencies());
            assertTrue(candidates.capturedIndex() >= 0, "chest stream index must be captured");
            assertTrue(
                    candidates.frequencies().size() >= 2,
                    "window should produce multiple candidates");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static StructureStart findStronghold(VanillaRuntime runtime, BlockPos target) {
        List<ChunkPos> rings =
                runtime.ringPositions("minecraft:strongholds", "minecraft:overworld");
        for (ChunkPos chunk : rings) {
            StructureStart start =
                    runtime.generateStructure("minecraft:stronghold", "minecraft:overworld", chunk);
            if (MinecraftVersionAdapter.isValidStart(start)
                    && start.getBoundingBox().isInside(target)) {
                return start;
            }
        }
        return null;
    }
}
