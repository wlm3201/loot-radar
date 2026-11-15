package dev.lootradar.core.search;

import java.util.List;
import java.util.Map;

/** 搜索结束汇总；所有 elapsed/cost/duration 字段单位均为纳秒，warnings 面向 UI 直接展示。 */
public record SearchReport(
        boolean completed,
        boolean cancelled,
        Map<String, Integer> foundPerItem,
        Map<String, Long> elapsedNanosPerItem,
        Map<String, Long> costNanosPerItem,
        Map<String, Long> elapsedNanosPerStructure,
        long totalElapsedNanos,
        int structuresScanned,
        int containersReplayed,
        List<String> warnings,
        Map<String, Long> itemDurationNanos) {}
