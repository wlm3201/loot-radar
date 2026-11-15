package dev.lootradar.core.search;

import java.util.Map;

/**
 * 每次命中后推送的进度快照。{@code elapsedNanosPerItem} 是「找到配额时」的时刻， {@code costNanosPerItem} 是累计投入成本，{@code
 * itemDurationNanos} 是 UI 展示用耗时。
 */
public record SearchProgress(
        int structuresScanned,
        int containersReplayed,
        Map<String, Integer> foundPerItem,
        String currentStructureId,
        long elapsedNanos,
        Map<String, Long> elapsedNanosPerItem,
        Map<String, Long> costNanosPerItem,
        Map<String, Long> itemDurationNanos,
        Map<String, Long> elapsedNanosPerStructure) {}
