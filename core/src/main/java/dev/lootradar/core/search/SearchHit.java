package dev.lootradar.core.search;

/**
 * 一条命中。{@code nth} 大于 0 表示序列型来源（第 n 次开箱/击杀）， {@code instanceIndex} 是同类结构在本次搜索中的序号，{@code estimated}
 * 表示 尚未扫描到真实宝库/海底神殿、坐标退化为搜索中心。
 */
public record SearchHit(
        String itemId,
        String itemZhName,
        LootMethod method,
        int x,
        int y,
        int z,
        String dimension,
        String structureId,
        String structureZhName,
        int count,
        int nth,
        int instanceIndex,
        boolean estimated,
        long elapsedNanos,
        long worldSeed,
        long workNanos) {}
