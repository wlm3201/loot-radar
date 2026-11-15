package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 宝库/远古守卫者序列预测与原生 RandomSequence 手动重放的逐次一致性。 */
class SequencePredictorsTest {

    private static VanillaRuntime runtime;
    private static VanillaLootOracle oracle;

    @BeforeAll
    static void setUp() {
        runtime = VanillaRuntime.load(0L);
        oracle = new VanillaLootOracle(runtime);
    }

    @AfterAll
    static void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void vaultRewardSequenceYieldsBoltTemplate() {
        Set<String> targets = Set.of("minecraft:bolt_armor_trim_smithing_template");
        List<VaultSequencePredictor.OpenResult> hits =
                VaultSequencePredictor.findHits(
                        oracle,
                        0L,
                        "minecraft:chests/trial_chambers/reward",
                        "minecraft:chests/trial_chambers/reward",
                        1,
                        300,
                        targets);
        assertTrue(!hits.isEmpty(), "expected at least one bolt template in 300 opens at seed 0");

        List<VaultSequencePredictor.OpenResult> again =
                VaultSequencePredictor.findHits(
                        oracle,
                        0L,
                        "minecraft:chests/trial_chambers/reward",
                        "minecraft:chests/trial_chambers/reward",
                        1,
                        300,
                        targets);
        assertEquals(hits, again, "sequence prediction must be deterministic");
    }

    @Test
    void vaultStartOffsetSkipsEarlierOpens() {
        String table = "minecraft:chests/trial_chambers/reward";
        String key = "minecraft:chests/trial_chambers/reward";
        List<VaultSequencePredictor.OpenResult> all =
                VaultSequencePredictor.preview(oracle, 0L, table, key, 1, 50);
        List<VaultSequencePredictor.OpenResult> later =
                VaultSequencePredictor.preview(oracle, 0L, table, key, 21, 30);
        assertEquals(
                all.subList(20, 50), later, "offset windows must line up with the same stream");
    }

    @Test
    void elderGuardianTideTemplateAppearsWithin100Kills() {
        for (int looting = 0; looting <= 3; looting++) {
            List<ElderGuardianPredictor.KillResult> hits =
                    ElderGuardianPredictor.findHits(
                            oracle, 0L, looting, 1, 200, ElderGuardianPredictor.TIDE_TEMPLATE);
            assertTrue(
                    !hits.isEmpty(),
                    "expected tide template within 200 kills at seed 0, looting " + looting);
            List<ElderGuardianPredictor.KillResult> again =
                    ElderGuardianPredictor.findHits(
                            oracle, 0L, looting, 1, 200, ElderGuardianPredictor.TIDE_TEMPLATE);
            assertEquals(hits, again, "deterministic at looting " + looting);
        }
    }

    @Test
    void elderGuardianStartOffsetLinesUp() {
        var stream =
                new net.minecraft.world.RandomSequence(
                                0L,
                                net.minecraft.resources.Identifier.parse(
                                        ElderGuardianPredictor.SEQUENCE_KEY))
                        .random();
        List<List<LootStack>> all = new java.util.ArrayList<>();
        for (int k = 1; k <= 40; k++) {
            all.add(ElderGuardianPredictor.rollKill(oracle, stream, 3));
        }

        var replay =
                new net.minecraft.world.RandomSequence(
                                0L,
                                net.minecraft.resources.Identifier.parse(
                                        ElderGuardianPredictor.SEQUENCE_KEY))
                        .random();
        for (int k = 1; k <= 15; k++) {
            ElderGuardianPredictor.rollKill(oracle, replay, 3);
        }
        for (int k = 16; k <= 40; k++) {
            var items = ElderGuardianPredictor.rollKill(oracle, replay, 3);
            assertEquals(all.get(k - 1), items, "kill " + k + " must match the same stream");
        }
    }
}
