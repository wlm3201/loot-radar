package dev.lootradar.engine;

import java.util.List;
import net.minecraft.core.BlockPos;

/** 一次放置过程中捕获到的容器。{@code lootSeed == 0} 是原版的「未设种子」哨兵值， 宝库（VAULT）恒为 0，其掉落改由 random_sequence 序列决定。 */
public record ContainerRecord(
        BlockPos pos,
        String lootTable,
        long lootSeed,
        ContainerKind kind,
        String blockId,
        List<String> potSherds) {
    public boolean hasLootTable() {
        return !lootTable.isEmpty();
    }
}
