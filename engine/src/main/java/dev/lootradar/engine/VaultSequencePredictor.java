package dev.lootradar.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequence;

/**
 * 试炼密室宝库 / 不祥宝库的序列预测：掉落由 {@code RandomSequence(worldSeed, tableId)} 决定，与箱子坐标无关，只需要「第几次开启」。
 *
 * <p>原版 {@code VaultBlockEntity} 用 {@code getRandomItems(params)} 走 random_sequence， 嵌套战利品表沿用同一个
 * {@code LootContext} 随机源，所以这里连续调用 {@code oracle.rollWithRandom} 即可复现。
 */
public final class VaultSequencePredictor {

    public record OpenResult(int nthOpen, List<LootStack> items) {
        public boolean containsAny(Set<String> targetItems) {
            return items.stream().anyMatch(stack -> targetItems.contains(stack.itemId()));
        }
    }

    private VaultSequencePredictor() {}

    public static List<OpenResult> findHits(
            VanillaLootOracle oracle,
            long worldSeed,
            String lootTableId,
            String sequenceKey,
            int startOffset,
            int maxOpens,
            Set<String> targetItems) {
        if (startOffset < 1) {
            throw new IllegalArgumentException("startOffset must be >= 1");
        }
        if (maxOpens < 1) {
            throw new IllegalArgumentException("maxOpens must be >= 1");
        }
        RandomSource stream = new RandomSequence(worldSeed, Identifier.parse(sequenceKey)).random();
        for (int i = 1; i < startOffset; i++) {
            oracle.rollWithRandom(lootTableId, stream);
        }
        List<OpenResult> hits = new ArrayList<>();
        for (int open = startOffset; open < startOffset + maxOpens; open++) {
            List<LootStack> items = oracle.rollWithRandom(lootTableId, stream);
            OpenResult result = new OpenResult(open, items);
            if (result.containsAny(targetItems)) {
                hits.add(result);
            }
        }
        return List.copyOf(hits);
    }

    public static List<OpenResult> preview(
            VanillaLootOracle oracle,
            long worldSeed,
            String lootTableId,
            String sequenceKey,
            int startOffset,
            int maxOpens) {
        RandomSource stream = new RandomSequence(worldSeed, Identifier.parse(sequenceKey)).random();
        for (int i = 1; i < startOffset; i++) {
            oracle.rollWithRandom(lootTableId, stream);
        }
        List<OpenResult> results = new ArrayList<>();
        for (int open = startOffset; open < startOffset + maxOpens; open++) {
            results.add(new OpenResult(open, oracle.rollWithRandom(lootTableId, stream)));
        }
        return List.copyOf(results);
    }
}
