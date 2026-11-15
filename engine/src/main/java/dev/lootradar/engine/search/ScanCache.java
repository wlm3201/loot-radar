package dev.lootradar.engine.search;

import dev.lootradar.engine.ContainerRecord;
import java.util.LinkedHashMap;
import java.util.List;

/** 扫描结果 LRU：键含世界种子、数据版本、维度、结构 id 与区块坐标， 因此切换版本或种子不会误用旧结果。矿井因依赖跨区块引用，不进入缓存。 */
public final class ScanCache {

    public record Key(
            long worldSeed,
            String versionId,
            String dimensionId,
            String structureId,
            int chunkX,
            int chunkZ) {}

    public record Result(List<ContainerRecord> sources, boolean scanned) {
        public Result {
            sources = List.copyOf(sources);
        }
    }

    private final int capacity;
    private final LinkedHashMap<Key, Result> map;

    public ScanCache(int capacity) {
        this.capacity = capacity;
        this.map =
                new LinkedHashMap<Key, Result>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<Key, Result> eldest) {
                        return size() > ScanCache.this.capacity;
                    }
                };
    }

    public static ScanCache disabled() {
        return new ScanCache(0);
    }

    public Result get(Key key) {
        if (capacity <= 0) return null;
        synchronized (map) {
            return map.get(key);
        }
    }

    public void put(Key key, Result result) {
        if (capacity <= 0) return;
        synchronized (map) {
            map.put(key, result);
        }
    }

    public int size() {
        synchronized (map) {
            return map.size();
        }
    }
}
