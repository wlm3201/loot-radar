package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 地形缓存的并发 single-flight、LRU 淘汰、失败重试与高度图 prime 校验。 */
class NativeTerrainCacheTest {
    private static VanillaRuntime runtime;

    @BeforeAll
    static void loadRuntime() {
        runtime = VanillaRuntime.load(0);
    }

    @AfterAll
    static void closeRuntime() {
        runtime.close();
    }

    private static ChunkAccess emptyTerrain(ChunkPos position) {
        var chunk =
                new ProtoChunk(
                        position,
                        UpgradeData.EMPTY,
                        runtime.heightAccessor("minecraft:overworld"),
                        PalettedContainerFactory.create(runtime.registries()),
                        null);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return chunk;
    }

    @Test
    void concurrentRequestsShareOneGeneration() throws Exception {
        var cache = new NativeTerrainCache(2);
        var position = new ChunkPos(2, 3);
        var ready = new CountDownLatch(6);
        var generateStarted = new CountDownLatch(1);
        var releaseGeneration = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<Future<ChunkAccess>>();
            for (int i = 0; i < 6; i++)
                results.add(
                        workers.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                                    return cache.get(
                                            position,
                                            () -> {
                                                calls.incrementAndGet();
                                                generateStarted.countDown();
                                                try {
                                                    if (!releaseGeneration.await(
                                                            10, TimeUnit.SECONDS))
                                                        throw new AssertionError(
                                                                "Generation was not released");
                                                } catch (InterruptedException exception) {
                                                    Thread.currentThread().interrupt();
                                                    throw new RuntimeException(exception);
                                                }
                                                return emptyTerrain(position);
                                            });
                                }));
            try {
                assertTrue(generateStarted.await(10, TimeUnit.SECONDS));
            } finally {
                releaseGeneration.countDown();
            }
            ChunkAccess first = results.getFirst().get(10, TimeUnit.SECONDS);
            for (var result : results) assertSame(first, result.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void leastRecentlyUsedEntryIsEvicted() {
        var cache = new NativeTerrainCache(2);
        var a = new ChunkPos(1, 0);
        var b = new ChunkPos(2, 0);
        var c = new ChunkPos(3, 0);
        ChunkAccess terrainA = cache.get(a, () -> emptyTerrain(a));
        ChunkAccess terrainB = cache.get(b, () -> emptyTerrain(b));
        assertSame(
                terrainA,
                cache.get(
                        a,
                        () -> {
                            throw new AssertionError("Cache miss");
                        }));
        cache.get(c, () -> emptyTerrain(c));
        assertSame(
                terrainA,
                cache.get(
                        a,
                        () -> {
                            throw new AssertionError("Recently used entry was evicted");
                        }));
        assertNotSame(terrainB, cache.get(b, () -> emptyTerrain(b)));
    }

    @Test
    void failedGenerationCanBeRetried() {
        var cache = new NativeTerrainCache();
        var position = new ChunkPos(-1, -1);
        var failure = new IllegalStateException("Synthetic generation failure");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                cache.get(
                                        position,
                                        () -> {
                                            throw failure;
                                        })));
        ChunkAccess terrain = emptyTerrain(position);
        assertSame(terrain, cache.get(position, () -> terrain));
    }

    @Test
    void unprimedTerrainIsRejectedBeforeSharing() {
        var cache = new NativeTerrainCache();
        var position = new ChunkPos(0, 0);
        assertThrows(
                IllegalStateException.class,
                () ->
                        cache.get(
                                position,
                                () ->
                                        new ProtoChunk(
                                                position,
                                                UpgradeData.EMPTY,
                                                runtime.heightAccessor("minecraft:overworld"),
                                                PalettedContainerFactory.create(
                                                        runtime.registries()),
                                                null)));
        ChunkAccess terrain = emptyTerrain(position);
        assertSame(terrain, cache.get(position, () -> terrain));
    }
}
