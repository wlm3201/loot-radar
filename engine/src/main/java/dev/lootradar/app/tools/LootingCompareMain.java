package dev.lootradar.app.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lootradar.engine.ElderGuardianPredictor;
import dev.lootradar.engine.LootStack;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 用 {@code loot-radar-looting/1} 真值回放 {@link dev.lootradar.engine.ElderGuardianPredictor}： 按 0..3
 * 级抢夺逐次击杀比对掉落，验证序列模型的随机消耗顺序。
 */
public final class LootingCompareMain {

    public static void main(String[] args) throws Exception {
        Path truthPath = Path.of(args[0]);
        JsonObject root = JsonParser.parseString(Files.readString(truthPath)).getAsJsonObject();
        long seed = root.get("seed").getAsLong();
        var kills = root.getAsJsonArray("kills");
        System.out.println("truth " + truthPath + " seed=" + seed + " kills=" + kills.size());
        try (VanillaRuntime runtime = VanillaRuntime.load(seed)) {
            VanillaLootOracle oracle = new VanillaLootOracle(runtime);
            net.minecraft.util.RandomSource stream =
                    new net.minecraft.world.RandomSequence(
                                    seed,
                                    net.minecraft.resources.Identifier.parse(
                                            ElderGuardianPredictor.SEQUENCE_KEY))
                            .random();
            int matched = 0;
            Map<Integer, int[]> perLevel = new TreeMap<>();
            List<String> failures = new ArrayList<>();
            for (var element : kills) {
                JsonObject row = element.getAsJsonObject();
                int nth = row.get("nth").getAsInt();
                int looting = row.get("looting").getAsInt();
                Map<String, Integer> actual = new LinkedHashMap<>();
                for (var item : row.getAsJsonArray("items")) {
                    JsonObject it = item.getAsJsonObject();
                    actual.merge(
                            it.get("item").getAsString(), it.get("count").getAsInt(), Integer::sum);
                }
                Map<String, Integer> expected = new LinkedHashMap<>();
                for (LootStack stack : ElderGuardianPredictor.rollKill(oracle, stream, looting)) {
                    expected.merge(stack.itemId(), stack.count(), Integer::sum);
                }
                int[] cell = perLevel.computeIfAbsent(looting, k -> new int[2]);
                cell[0]++;
                if (expected.equals(actual)) {
                    matched++;
                    cell[1]++;
                } else if (failures.size() < 12) {
                    failures.add(
                            "nth="
                                    + nth
                                    + " looting="
                                    + looting
                                    + "\n    expected "
                                    + new TreeMap<>(expected)
                                    + "\n    actual   "
                                    + new TreeMap<>(actual));
                }
            }
            System.out.println("matched " + matched + "/" + kills.size());
            for (var entry : perLevel.entrySet()) {
                int[] cell = entry.getValue();
                System.out.println("  looting " + entry.getKey() + ": " + cell[1] + "/" + cell[0]);
            }
            for (String failure : failures) {
                System.out.println("MISMATCH " + failure);
            }
            System.exit(matched == kills.size() ? 0 : 1);
        }
    }

    private LootingCompareMain() {}
}
