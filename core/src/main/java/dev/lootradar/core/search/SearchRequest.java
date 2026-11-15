package dev.lootradar.core.search;

import dev.lootradar.core.version.GameVersion;
import java.util.List;

/**
 * 一次搜索的输入。{@code radius} 是方块半径（按区块中心距离裁剪）， {@code minCountPerItem} 是每个物品的配额，{@code startOffset}
 * 是宝库/生物 random_sequence 的起始开启或击杀序号，{@code structures} 为空表示不额外过滤来源。
 */
public record SearchRequest(
        long seed,
        GameVersion version,
        int centerX,
        int centerZ,
        int radius,
        int minCountPerItem,
        int lootingLevel,
        int startOffset,
        boolean skipDungeon,
        List<String> itemIds,
        int itemBudgetSeconds,
        List<String> structures) {
    public SearchRequest {
        if (radius <= 0 || radius > 500_000) {
            throw new IllegalArgumentException("radius must be 1..500000");
        }
        // 与前端一致的范围约束：直接调用 API 时也不允许越界坐标导致溢出。
        if (centerX < -30_000_000
                || centerX > 30_000_000
                || centerZ < -30_000_000
                || centerZ > 30_000_000) {
            throw new IllegalArgumentException("centerX/centerZ must be within +-30000000");
        }
        if (minCountPerItem <= 0 || minCountPerItem > 9999) {
            throw new IllegalArgumentException("minCountPerItem must be 1..9999");
        }
        if (lootingLevel < 0 || lootingLevel > 3) {
            throw new IllegalArgumentException("lootingLevel must be 0..3");
        }
        if (startOffset < 1 || startOffset > 100_000) {
            throw new IllegalArgumentException("startOffset must be 1..100000");
        }
        itemIds = List.copyOf(itemIds);
        if (itemIds.isEmpty()) {
            throw new IllegalArgumentException("itemIds must not be empty");
        }
        if (itemBudgetSeconds < 0) {
            throw new IllegalArgumentException("itemBudgetSeconds must be >= 0");
        }
        structures = List.copyOf(structures);
    }

    public SearchRequest(
            long seed,
            GameVersion version,
            int centerX,
            int centerZ,
            int radius,
            int minCountPerItem,
            int lootingLevel,
            int startOffset,
            boolean skipDungeon,
            List<String> itemIds) {
        this(
                seed,
                version,
                centerX,
                centerZ,
                radius,
                minCountPerItem,
                lootingLevel,
                startOffset,
                skipDungeon,
                itemIds,
                60,
                List.of());
    }

    public static SearchRequest defaults(long seed, GameVersion version, List<String> itemIds) {
        return new SearchRequest(seed, version, 0, 0, 5000, 3, 3, 1, true, itemIds, 60, List.of());
    }
}
