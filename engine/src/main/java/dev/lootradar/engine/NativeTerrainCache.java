package dev.lootradar.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 原生地形区块的 LRU 缓存 + single-flight：同一区块只生成一次，其它线程等待 {@code CompletableFuture}。生成后强制检查所有高度图已
 * prime，否则结构放置会读到 不一致的高度，导致高度敏感结构坐标漂移。
 */
public final class NativeTerrainCache {
    private static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final Object lock = new Object();
    private final LinkedHashMap<ChunkPos, ChunkAccess> completed =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<ChunkPos, Pending> inFlight = new HashMap<>();

    public NativeTerrainCache() {
        this(DEFAULT_CAPACITY);
    }

    public NativeTerrainCache(int capacity) {
        if (capacity < 1)
            throw new IllegalArgumentException("Terrain cache capacity must be positive");
        this.capacity = capacity;
    }

    // 同线程重入视为错误（说明生成过程递归依赖自身），其它线程等待同一 Future。
    public ChunkAccess get(ChunkPos position, Supplier<ChunkAccess> supplier) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(supplier, "supplier");
        Pending pending;
        boolean generate;
        synchronized (lock) {
            ChunkAccess cached = completed.get(position);
            if (cached != null) return cached;
            pending = inFlight.get(position);
            generate = pending == null;
            if (generate) {
                pending = new Pending(Thread.currentThread(), new CompletableFuture<>());
                inFlight.put(position, pending);
            } else if (pending.owner() == Thread.currentThread()) {
                throw new IllegalStateException("Recursive terrain generation for " + position);
            }
        }

        if (generate) {
            try {
                ChunkAccess generated = Objects.requireNonNull(supplier.get(), "Generated terrain");
                if (!position.equals(generated.getPos())) {
                    throw new IllegalStateException(
                            "Terrain position mismatch: requested "
                                    + position
                                    + ", received "
                                    + generated.getPos());
                }
                for (Heightmap.Types type : Heightmap.Types.values()) {
                    if (!generated.hasPrimedHeightmap(type)) {
                        throw new IllegalStateException(
                                "Terrain heightmap must be primed before publication: "
                                        + position
                                        + " / "
                                        + type);
                    }
                }
                synchronized (lock) {
                    completed.put(position, generated);
                    while (completed.size() > capacity) completed.pollFirstEntry();
                    inFlight.remove(position, pending);
                }
                pending.result().complete(generated);
                return generated;
            } catch (Throwable error) {
                synchronized (lock) {
                    inFlight.remove(position, pending);
                }
                pending.result().completeExceptionally(error);
                throw propagate(error);
            }
        }

        try {
            return pending.result().join();
        } catch (CompletionException error) {
            throw propagate(error.getCause());
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtimeError) return runtimeError;
        if (error instanceof Error fatalError) throw fatalError;
        return new CompletionException(error);
    }

    private record Pending(Thread owner, CompletableFuture<ChunkAccess> result) {}
}
