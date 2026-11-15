package dev.lootradar.engine.search;

import dev.lootradar.core.catalog.ItemCatalog;
import dev.lootradar.core.catalog.ItemDef;
import dev.lootradar.core.catalog.ItemSource;
import dev.lootradar.core.search.LootMethod;
import dev.lootradar.core.search.SearchHit;
import dev.lootradar.core.search.SearchProgress;
import dev.lootradar.core.search.SearchReport;
import dev.lootradar.core.search.SearchRequest;
import dev.lootradar.core.structure.PlacementSpec;
import dev.lootradar.core.structure.RandomSpreadLocator;
import dev.lootradar.core.structure.StructureSetSpec;
import dev.lootradar.core.structure.StructureSpec;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.ContainerRecord;
import dev.lootradar.engine.ElderGuardianPredictor;
import dev.lootradar.engine.FeatureScanner;
import dev.lootradar.engine.LootStack;
import dev.lootradar.engine.MineshaftBiomes;
import dev.lootradar.engine.StructureScanner;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import dev.lootradar.engine.VaultSequencePredictor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * 搜索总控：把「物品 → 来源结构」展开成按距离分带的扫描任务，并行重放结构放置与战利品表。
 *
 * <p>先单独处理序列型来源（宝库、远古守卫者），再以 750 格为首带、之后逐带加倍地 定位候选区块；每个带内按距离排序提交线程池，命中通过 {@link Listener} 实时推送，
 * 直至所有物品达到配额、单物品时间预算耗尽或被取消。扫描结果由 {@link ScanCache} 按 (seed, version, dimension, structure, chunk)
 * 复用。
 */
public final class SearchEngine {

    // 序列型来源最多向前推演 2000 次开启/击杀，命中不到时给出警告而非无限循环。
    private static final int MAX_SEQUENCE_ROLLS = 2000;

    private static long itemBudgetNanos(SearchRequest request) {
        return request.itemBudgetSeconds() == 0
                ? Long.MAX_VALUE
                : request.itemBudgetSeconds() * 1_000_000_000L;
    }

    private boolean itemBudgetLeft(
            Work work,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchRequest request,
            SearchState state) {
        long budget = itemBudgetNanos(request);
        boolean anyLeft = false;
        for (var entry : targets.entrySet()) {
            if (remaining.getOrDefault(entry.getKey(), 0) <= 0
                    || !canContain(entry.getValue(), work.structureId())) {
                continue;
            }
            if (state.costNanosPerItem.getOrDefault(entry.getKey(), 0L) < budget) {
                anyLeft = true;
            } else {
                state.noteItemCapHit(entry.getKey(), entry.getValue().zhName(), budget);
            }
        }
        return anyLeft;
    }

    public interface Listener {
        void onHit(SearchHit hit);

        void onProgress(SearchProgress progress);
    }

    private final VanillaRuntime runtime;
    private final VanillaLootOracle oracle;
    private final VersionRegistry versionRegistry;
    private final ItemCatalog catalog;
    private final int threads;
    private final ScanCache scanCache;

    public SearchEngine(
            VanillaRuntime runtime,
            VanillaLootOracle oracle,
            VersionRegistry versionRegistry,
            ItemCatalog catalog) {
        this(
                runtime,
                oracle,
                versionRegistry,
                catalog,
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                ScanCache.disabled());
    }

    public SearchEngine(
            VanillaRuntime runtime,
            VanillaLootOracle oracle,
            VersionRegistry versionRegistry,
            ItemCatalog catalog,
            ScanCache scanCache) {
        this(
                runtime,
                oracle,
                versionRegistry,
                catalog,
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                scanCache);
    }

    public SearchEngine(
            VanillaRuntime runtime,
            VanillaLootOracle oracle,
            VersionRegistry versionRegistry,
            ItemCatalog catalog,
            int threads) {
        this(runtime, oracle, versionRegistry, catalog, threads, ScanCache.disabled());
    }

    public SearchEngine(
            VanillaRuntime runtime,
            VanillaLootOracle oracle,
            VersionRegistry versionRegistry,
            ItemCatalog catalog,
            int threads,
            ScanCache scanCache) {
        this.runtime = runtime;
        this.oracle = oracle;
        this.versionRegistry = versionRegistry;
        this.catalog = catalog;
        this.threads = threads;
        this.scanCache = scanCache;
    }

    public SearchReport search(SearchRequest request, Listener listener, AtomicBoolean cancelled) {
        if (request.seed() != runtime.worldSeed()) {
            throw new IllegalArgumentException("Search seed does not match the worldgen runtime");
        }
        long startedAt = System.nanoTime();
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, ItemDef> targets = new LinkedHashMap<>();
        for (String itemId : request.itemIds()) {
            ItemDef def = catalog.item(itemId);
            targets.put(itemId, def);
            remaining.put(itemId, request.minCountPerItem());
        }

        Plan plan = planSources(request, targets);
        SearchState state = new SearchState(startedAt);
        state.warnings.addAll(plan.warnings());

        if (!cancelled.get() && (plan.needVault() || plan.needOminousVault())) {
            long phaseStarted = System.nanoTime();
            processVaultSequences(request, plan, targets, remaining, state, listener);
            long phaseNanos = System.nanoTime() - phaseStarted;
            attributePhase(
                    state,
                    targets,
                    remaining,
                    phaseNanos,
                    java.util.Set.of(
                            dev.lootradar.core.search.LootMethod.VAULT,
                            dev.lootradar.core.search.LootMethod.VAULT_OMINOUS));
            if (plan.needVault()) {
                state.addStructureTime("seq:vault", phaseNanos);
            }
            if (plan.needOminousVault()) {
                state.addStructureTime("seq:vault_ominous", phaseNanos);
            }
        }
        if (!cancelled.get() && plan.needElderGuardian()) {
            long phaseStarted = System.nanoTime();
            processElderGuardian(request, plan, targets, remaining, state, listener);
            long phaseNanos = System.nanoTime() - phaseStarted;
            attributePhase(
                    state,
                    targets,
                    remaining,
                    phaseNanos,
                    java.util.Set.of(dev.lootradar.core.search.LootMethod.ENTITY));
            state.addStructureTime("seq:entity", phaseNanos);
        }

        // 距离分带：先扫近处再逐步扩大，保证「最近的 n 个命中」优先返回，
        // 也避免一开始就把整个半径内的所有结构一次性定位完。
        java.util.List<long[]> bands = new java.util.ArrayList<>();
        long bandStart = 0;
        long edge = 750;
        while (true) {
            long bandEnd = Math.min(edge, request.radius());
            bands.add(new long[] {bandStart, bandEnd});
            if (bandEnd >= request.radius()) {
                break;
            }
            bandStart = bandEnd;
            edge = bandEnd + Math.max(750, bandEnd);
        }

        int parallelism =
                (plan.structureIds().stream().anyMatch(MineshaftBiomes::isMineshaft)
                                || plan.featureIds().contains("minecraft:monster_room"))
                        ? Math.min(4, threads)
                        : threads;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        try {
            record InFlight(Work work, Future<WorkResult> future) {}
            for (long[] band : bands) {
                if (cancelled.get() || quotasMet(remaining)) {
                    break;
                }
                long locateStarted = System.nanoTime();
                List<Work> works =
                        locate(request, plan, state, targets, remaining, band[0], band[1]);
                works.sort((a, b) -> Long.compare(a.distanceSq(), b.distanceSq()));
                java.util.ArrayDeque<InFlight> inFlight = new java.util.ArrayDeque<>();
                java.util.Iterator<Work> queue = works.iterator();
                while (true) {
                    while (inFlight.size() < parallelism
                            && queue.hasNext()
                            && !cancelled.get()
                            && !quotasMet(remaining)) {
                        Work work = queue.next();
                        if (!workUseful(work, targets, remaining, state)) {
                            continue;
                        }
                        if (!itemBudgetLeft(work, targets, remaining, request, state)) {
                            continue;
                        }
                        inFlight.add(
                                new InFlight(
                                        work,
                                        pool.submit(
                                                () ->
                                                        processWork(
                                                                request, work, targets,
                                                                cancelled))));
                    }
                    if (inFlight.isEmpty()) {
                        break;
                    }
                    InFlight oldest = inFlight.poll();
                    WorkResult result = await(oldest.future(), state);
                    if (result != null) {
                        mergeResult(request, result, targets, remaining, state, listener);
                    }
                    if (cancelled.get() || quotasMet(remaining)) {
                        break;
                    }
                }
            }
        } finally {
            pool.shutdown();
            pool.close();
        }

        for (String itemId : targets.keySet()) {
            state.costNanosPerItem.putIfAbsent(itemId, 0L);
        }

        boolean complete = quotasMet(remaining);
        return state.report(complete, cancelled.get(), remaining);
    }

    private static boolean quotasMet(Map<String, Integer> remaining) {
        return remaining.values().stream().allMatch(count -> count <= 0);
    }

    private boolean workUseful(
            Work work,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchState state) {
        for (Map.Entry<String, ItemDef> entry : targets.entrySet()) {
            if (remaining.getOrDefault(entry.getKey(), 0) <= 0) {
                continue;
            }
            for (ItemSource source : entry.getValue().sources()) {
                if (!source.structures().contains(work.structureId())) {
                    continue;
                }
                switch (source.method()) {
                    case "vault" -> {
                        if (state.vaultAnchor("minecraft:chests/trial_chambers/reward") == null) {
                            return true;
                        }
                    }
                    case "vault_ominous" -> {
                        if (state.vaultAnchor("minecraft:chests/trial_chambers/reward_ominous")
                                == null) {
                            return true;
                        }
                    }
                    case "entity" -> {
                        if (state.monumentAnchorPos == null) {
                            return true;
                        }
                    }
                    default -> {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private WorkResult await(Future<WorkResult> future, SearchState state) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException exception) {
            state.warnings.add("结构任务失败（已跳过该处）：" + exception.getCause());
            return null;
        }
    }

    private record Plan(
            Set<String> structureIds,
            Set<String> featureIds,
            boolean needVault,
            boolean needOminousVault,
            boolean needElderGuardian,
            List<String> warnings) {}

    private Plan planSources(SearchRequest request, Map<String, ItemDef> targets) {
        Set<String> structureIds = new LinkedHashSet<>();
        Set<String> featureIds = new LinkedHashSet<>();
        boolean needVault = false;
        boolean needOminousVault = false;
        boolean needElderGuardian = false;
        List<String> warnings = new ArrayList<>();

        for (ItemDef def : targets.values()) {
            Plan perItem = planItem(request, def);
            if (perItem.structureIds().isEmpty()
                    && perItem.featureIds().isEmpty()
                    && !perItem.needVault()
                    && !perItem.needOminousVault()
                    && !perItem.needElderGuardian()) {
                warnings.add("跳过设置导致 " + def.zhName() + " 无可用来源，已忽略跳过");
                perItem = planItemIgnoringSkips(def);
            }
            structureIds.addAll(perItem.structureIds());
            featureIds.addAll(perItem.featureIds());
            needVault |= perItem.needVault();
            needOminousVault |= perItem.needOminousVault();
            needElderGuardian |= perItem.needElderGuardian();
        }
        return new Plan(
                structureIds, featureIds, needVault, needOminousVault, needElderGuardian, warnings);
    }

    private Plan planItem(SearchRequest request, ItemDef def) {
        Set<String> structureIds = new LinkedHashSet<>();
        Set<String> featureIds = new LinkedHashSet<>();
        boolean needVault = false;
        boolean needOminousVault = false;
        boolean needElderGuardian = false;
        for (ItemSource source : def.sources()) {
            if (!request.structures().isEmpty()
                    && source.structures().stream().noneMatch(request.structures()::contains)) {
                continue;
            }
            switch (source.method()) {
                case "vault" -> needVault = true;
                case "vault_ominous" -> needOminousVault = true;
                case "entity" -> needElderGuardian = true;
                default -> {}
            }
            collectStructures(
                    source, request.skipDungeon(), request.structures(), structureIds, featureIds);
        }
        return new Plan(
                structureIds,
                featureIds,
                needVault,
                needOminousVault,
                needElderGuardian,
                List.of());
    }

    private Plan planItemIgnoringSkips(ItemDef def) {
        Set<String> structureIds = new LinkedHashSet<>();
        Set<String> featureIds = new LinkedHashSet<>();
        boolean needVault = false;
        boolean needOminousVault = false;
        boolean needElderGuardian = false;
        for (ItemSource source : def.sources()) {
            switch (source.method()) {
                case "vault" -> needVault = true;
                case "vault_ominous" -> needOminousVault = true;
                case "entity" -> needElderGuardian = true;
                default -> {}
            }
            collectStructures(source, false, List.of(), structureIds, featureIds);
        }
        return new Plan(
                structureIds,
                featureIds,
                needVault,
                needOminousVault,
                needElderGuardian,
                List.of());
    }

    private void collectStructures(
            ItemSource source,
            boolean skipDungeon,
            List<String> structureFilter,
            Set<String> structureIds,
            Set<String> featureIds) {
        for (String structureId : source.structures()) {
            if (!structureFilter.isEmpty() && !structureFilter.contains(structureId)) {
                continue;
            }
            if (skipDungeon && structureId.equals("minecraft:monster_room")) {
                continue;
            }
            StructureSpec spec = versionRegistry.structure(structureId);
            PlacementSpec.Type type = versionRegistry.sets().get(spec.set()).placement().type();
            if (type == PlacementSpec.Type.FEATURE) {
                featureIds.add(structureId);
            } else {
                structureIds.add(structureId);
            }
        }
    }

    private enum WorkKind {
        STRUCTURE,
        FEATURE
    }

    private record Work(
            WorkKind kind,
            String structureId,
            String dimensionId,
            ChunkPos chunk,
            long distanceSq) {}

    private List<Work> locate(
            SearchRequest request,
            Plan plan,
            SearchState state,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            long minDistance,
            long maxDistance) {
        List<Work> works = new ArrayList<>();
        int centerChunkX = Math.floorDiv(request.centerX(), 16);
        int centerChunkZ = Math.floorDiv(request.centerZ(), 16);
        int radiusChunks = Math.floorDiv((int) maxDistance, 16) + 1;

        for (String structureId : plan.structureIds()) {
            long typeStarted = System.nanoTime();
            StructureSpec spec = versionRegistry.structure(structureId);
            StructureSetSpec set = versionRegistry.sets().get(spec.set());
            List<ChunkPos> candidates = new ArrayList<>();
            PlacementSpec placement = set.placement();
            if (placement.isRandomSpread()) {
                for (RandomSpreadLocator.Candidate candidate :
                        RandomSpreadLocator.candidatesInRadius(
                                set,
                                request.seed(),
                                centerChunkX,
                                centerChunkZ,
                                radiusChunks,
                                versionRegistry.sets())) {
                    candidates.add(new ChunkPos(candidate.chunkX(), candidate.chunkZ()));
                }
            } else {
                candidates.addAll(runtime.ringPositions(set.id(), spec.dimension()));
            }
            for (ChunkPos chunk : candidates) {
                long distanceSq = distanceSq(request, chunk);
                if (distanceSq < minDistance * minDistance
                        || distanceSq >= (long) maxDistance * maxDistance) {
                    continue;
                }
                works.add(
                        new Work(
                                WorkKind.STRUCTURE,
                                structureId,
                                spec.dimension(),
                                chunk,
                                distanceSq));
            }
            attributeLocate(
                    state, targets, remaining, structureId, System.nanoTime() - typeStarted);
        }

        for (String featureId : plan.featureIds()) {
            long typeStarted = System.nanoTime();
            StructureSpec spec = versionRegistry.structure(featureId);
            Integer rarity = spec.rarity();
            int minX = centerChunkX - radiusChunks;
            int maxX = centerChunkX + radiusChunks;
            int minZ = centerChunkZ - radiusChunks;
            int maxZ = centerChunkZ + radiusChunks;
            for (int chunkX = minX; chunkX <= maxX; chunkX++) {
                for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                    ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                    long distanceSq = distanceSq(request, chunk);
                    if (distanceSq < minDistance * minDistance
                            || distanceSq >= (long) maxDistance * maxDistance) {
                        continue;
                    }
                    if (rarity != null
                            && !FeatureScanner.rarityAllows(
                                    runtime,
                                    featureId,
                                    spec.dimension(),
                                    chunk,
                                    rarity,
                                    versionRegistry)) {
                        continue;
                    }
                    works.add(
                            new Work(
                                    WorkKind.FEATURE,
                                    featureId,
                                    spec.dimension(),
                                    chunk,
                                    distanceSq));
                }
            }
            attributeLocate(state, targets, remaining, featureId, System.nanoTime() - typeStarted);
        }
        return works;
    }

    private static void attributeLocate(
            SearchState state,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            String structureId,
            long nanos) {
        state.addStructureTime(structureId, nanos);
        for (var entry : targets.entrySet()) {
            if (canContain(entry.getValue(), structureId)) {
                state.advanceItem(entry.getKey(), nanos);
                if (remaining.getOrDefault(entry.getKey(), 0) > 0) {
                    state.costNanosPerItem.merge(entry.getKey(), nanos, Long::sum);
                }
            }
        }
    }

    private static boolean canContain(ItemDef def, String structureId) {
        for (var source : def.sources()) {
            if (source.structures().contains(structureId)) {
                return true;
            }
        }
        return false;
    }

    private static void attributePhase(
            SearchState state,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            long nanos,
            java.util.Set<dev.lootradar.core.search.LootMethod> methods) {
        for (var entry : targets.entrySet()) {
            if (remaining.getOrDefault(entry.getKey(), 0) <= 0) {
                continue;
            }
            boolean relevant =
                    entry.getValue().sources().stream()
                            .anyMatch(source -> methods.contains(source.method()));
            if (relevant) {
                state.advanceItem(entry.getKey(), nanos);
                state.costNanosPerItem.merge(entry.getKey(), nanos, Long::sum);
            }
        }
    }

    private static long distanceSq(SearchRequest request, ChunkPos chunk) {
        long dx = (long) chunk.getMinBlockX() + 8 - request.centerX();
        long dz = (long) chunk.getMinBlockZ() + 8 - request.centerZ();
        return dx * dx + dz * dz;
    }

    private record RawHit(
            String itemId,
            LootMethod method,
            int x,
            int y,
            int z,
            String dimension,
            String structureId,
            String structureZhName,
            int count,
            int nth,
            boolean estimated) {}

    private record WorkResult(
            String structureId,
            boolean scanned,
            List<RawHit> hits,
            List<ContainerRecord> vaultSources,
            boolean monument,
            int containersReplayed,
            long elapsedNanos,
            long timedNanos,
            boolean failed) {}

    private WorkResult processWork(
            SearchRequest request,
            Work work,
            Map<String, ItemDef> targets,
            AtomicBoolean cancelled) {
        if (work.kind() == WorkKind.STRUCTURE
                && work.structureId().equals("minecraft:stronghold")) {
            synchronized (
                    net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces
                            .class) {
                return processWorkMeasured(request, work, targets, cancelled);
            }
        }
        return processWorkMeasured(request, work, targets, cancelled);
    }

    private WorkResult processWorkMeasured(
            SearchRequest request,
            Work work,
            Map<String, ItemDef> targets,
            AtomicBoolean cancelled) {
        long startedAt = System.nanoTime();
        try {
            return doProcessWork(request, work, targets, cancelled, startedAt);
        } catch (RuntimeException failure) {
            long elapsed = System.nanoTime() - startedAt;
            return new WorkResult(
                    work.structureId(),
                    false,
                    List.of(),
                    List.of(),
                    false,
                    0,
                    elapsed,
                    elapsed,
                    true);
        }
    }

    private WorkResult doProcessWork(
            SearchRequest request,
            Work work,
            Map<String, ItemDef> targets,
            AtomicBoolean cancelled,
            long startedAt) {
        List<RawHit> hits = new ArrayList<>();
        List<ContainerRecord> vaultSources = new ArrayList<>();
        int[] containersReplayed = {0};

        StructureSpec spec = versionRegistry.structure(work.structureId());
        boolean cacheable =
                work.kind() == WorkKind.FEATURE || !MineshaftBiomes.isMineshaft(work.structureId());
        ScanCache.Key key =
                cacheable
                        ? new ScanCache.Key(
                                request.seed(),
                                request.version().id,
                                work.dimensionId(),
                                work.structureId(),
                                work.chunk().x(),
                                work.chunk().z())
                        : null;
        ScanCache.Result cached = key == null ? null : scanCache.get(key);

        List<ContainerRecord> sources;
        boolean scanned;
        if (cached != null) {
            sources = cached.sources();
            scanned = cached.scanned();
        } else if (work.kind() == WorkKind.FEATURE) {
            sources =
                    FeatureScanner.scan(
                            runtime,
                            work.structureId(),
                            work.dimensionId(),
                            work.chunk(),
                            versionRegistry);
            scanned = !sources.isEmpty();
            if (key != null) scanCache.put(key, new ScanCache.Result(sources, scanned));
        } else {
            List<VanillaRuntime.StructureSetMember> members =
                    versionRegistry
                            .sets()
                            .get(versionRegistry.structure(work.structureId()).set())
                            .structures()
                            .stream()
                            .map(
                                    member ->
                                            new VanillaRuntime.StructureSetMember(
                                                    member.id(), member.weight()))
                            .toList();
            StructureStart start =
                    runtime.generateSelectedStructure(members, work.dimensionId(), work.chunk());
            if (!dev.lootradar.engine.MinecraftVersionAdapter.isValidStart(start)
                    || !runtime.registries()
                            .lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                            .getKey(start.getStructure())
                            .toString()
                            .equals(work.structureId())
                    || (MineshaftBiomes.isMineshaft(work.structureId())
                            && targets.values().stream()
                                    .noneMatch(
                                            item ->
                                                    !item.id().equals("minecraft:music_disc_bounce")
                                                            && item.sources().stream()
                                                                    .anyMatch(
                                                                            source ->
                                                                                    source.structures()
                                                                                            .contains(
                                                                                                    work
                                                                                                            .structureId())))
                            && !MineshaftBiomes.mayContainBounce(
                                    runtime, work.dimensionId(), start))) {
                sources = List.of();
                scanned = false;
            } else {
                boolean bounceOnly =
                        MineshaftBiomes.isMineshaft(work.structureId())
                                && targets.values().stream()
                                        .noneMatch(
                                                item ->
                                                        !item.id()
                                                                        .equals(
                                                                                "minecraft:music_disc_bounce")
                                                                && item.sources().stream()
                                                                        .anyMatch(
                                                                                source ->
                                                                                        source.structures()
                                                                                                .contains(
                                                                                                        work
                                                                                                                .structureId())));
                sources =
                        StructureScanner.scan(
                                runtime,
                                work.structureId(),
                                work.dimensionId(),
                                start,
                                bounceOnly
                                        ? MineshaftBiomes.bounceChunks(
                                                runtime, work.dimensionId(), start)
                                        : null,
                                versionRegistry);
                scanned = true;
            }
            if (key != null) scanCache.put(key, new ScanCache.Result(sources, scanned));
        }
        if (!scanned) {
            long elapsed = System.nanoTime() - startedAt;
            return new WorkResult(
                    work.structureId(),
                    false,
                    hits,
                    vaultSources,
                    false,
                    0,
                    elapsed,
                    cached != null ? 0L : elapsed,
                    false);
        }

        for (ContainerRecord source : sources) {
            if (cancelled.get()) {
                break;
            }
            switch (source.kind()) {
                case POT_SHERD -> collectPotSherds(spec, source, targets, hits);
                case VAULT -> vaultSources.add(source);
                case CONTAINER, ARCHAEOLOGY -> {
                    containersReplayed[0]++;
                    collectReplayable(request, spec, source, targets, hits);
                }
                case ENTITY -> {}
            }
        }
        long elapsed = System.nanoTime() - startedAt;
        return new WorkResult(
                work.structureId(),
                true,
                hits,
                vaultSources,
                work.structureId().equals("minecraft:monument"),
                containersReplayed[0],
                elapsed,
                cached != null ? 0L : elapsed,
                false);
    }

    private void collectPotSherds(
            StructureSpec spec,
            ContainerRecord source,
            Map<String, ItemDef> targets,
            List<RawHit> hits) {
        for (String sherd : source.potSherds()) {
            if (!targets.containsKey(sherd)) {
                continue;
            }
            hits.add(
                    new RawHit(
                            sherd,
                            LootMethod.POT_SHERD,
                            source.pos().getX(),
                            source.pos().getY(),
                            source.pos().getZ(),
                            spec.dimension(),
                            spec.id(),
                            spec.zhName(),
                            1,
                            -1,
                            false));
        }
    }

    private void collectReplayable(
            SearchRequest request,
            StructureSpec spec,
            ContainerRecord source,
            Map<String, ItemDef> targets,
            List<RawHit> hits) {
        List<LootStack> items =
                oracle.roll(
                        source.lootTable(),
                        source.lootSeed(),
                        spec.dimension(),
                        net.minecraft.world.phys.Vec3.atCenterOf(source.pos()));
        LootMethod method = methodOf(source);
        for (String sherd : source.potSherds()) {
            if (targets.containsKey(sherd)) {
                hits.add(
                        new RawHit(
                                sherd,
                                LootMethod.CORRIDOR_POT,
                                source.pos().getX(),
                                source.pos().getY(),
                                source.pos().getZ(),
                                spec.dimension(),
                                spec.id(),
                                spec.zhName(),
                                1,
                                -1,
                                false));
            }
        }
        for (LootStack stack : items) {
            if (!targets.containsKey(stack.itemId())) {
                continue;
            }
            hits.add(
                    new RawHit(
                            stack.itemId(),
                            method,
                            source.pos().getX(),
                            source.pos().getY(),
                            source.pos().getZ(),
                            spec.dimension(),
                            spec.id(),
                            spec.zhName(),
                            stack.count(),
                            -1,
                            source.blockId().equals("minecraft:chest_minecart")
                                    || source.lootTable().equals("minecraft:chests/simple_dungeon")
                                    || source.lootTable()
                                            .startsWith("minecraft:chests/stronghold_")));
        }
    }

    private static LootMethod methodOf(ContainerRecord source) {
        return switch (source.kind()) {
            case ARCHAEOLOGY -> LootMethod.ARCHAEOLOGY;
            case POT_SHERD -> LootMethod.POT_SHERD;
            case VAULT ->
                    source.lootTable().contains("ominous")
                            ? LootMethod.VAULT_OMINOUS
                            : LootMethod.VAULT;
            case ENTITY -> LootMethod.ENTITY;
            case CONTAINER ->
                    source.lootTable().equals("minecraft:pots/trial_chambers/corridor")
                            ? LootMethod.CORRIDOR_POT
                            : LootMethod.CHEST;
        };
    }

    private void mergeResult(
            SearchRequest request,
            WorkResult result,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchState state,
            Listener listener) {
        int instanceIndex = 0;
        if (result.scanned()) {
            state.structuresScanned++;
            instanceIndex = state.nextInstance(result.structureId());
        }
        if (result.failed()) {
            state.failedCounts.merge(result.structureId(), 1, Integer::sum);
        }
        state.containersReplayed += result.containersReplayed();
        state.addStructureTime(result.structureId(), result.timedNanos());
        for (var entry : targets.entrySet()) {
            if (canContain(entry.getValue(), result.structureId())) {
                state.advanceItem(entry.getKey(), result.timedNanos());
                if (remaining.getOrDefault(entry.getKey(), 0) > 0) {
                    state.costNanosPerItem.merge(entry.getKey(), result.elapsedNanos(), Long::sum);
                }
            }
        }
        if (result.monument() && state.monumentAnchorPos == null) {
            state.monumentAnchorPos = new int[] {request.centerX(), 64, request.centerZ()};
        }
        for (ContainerRecord vault : result.vaultSources()) {
            state.recordVaultSource(
                    vault.lootTable(), vault.pos().getX(), vault.pos().getY(), vault.pos().getZ());
        }
        Map<String, Long> rowValues = new LinkedHashMap<>();
        Map<String, Boolean> quotaCounting = new LinkedHashMap<>();
        for (RawHit hit : result.hits()) {
            rowValues.putIfAbsent(hit.itemId(), state.spanOf(hit.itemId()));
            quotaCounting.putIfAbsent(hit.itemId(), remaining.getOrDefault(hit.itemId(), 0) > 0);
        }
        for (RawHit hit : result.hits()) {
            emitHit(
                    request,
                    hit,
                    instanceIndex,
                    rowValues.getOrDefault(hit.itemId(), 0L),
                    targets,
                    remaining,
                    state,
                    listener);
        }
        for (String itemId : rowValues.keySet()) {
            state.closeSpan(itemId, quotaCounting.getOrDefault(itemId, false));
        }
    }

    private void emitHit(
            SearchRequest request,
            RawHit hit,
            int instanceIndex,
            long workNanos,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchState state,
            Listener listener) {
        ItemDef def = targets.get(hit.itemId());
        if (def == null) {
            return;
        }
        int before = remaining.getOrDefault(hit.itemId(), 0);
        if (before > 0) {
            remaining.put(hit.itemId(), before - Math.min(hit.count(), before));
        }
        int left = remaining.getOrDefault(hit.itemId(), 0);
        long elapsed = System.nanoTime() - state.startedAt;
        SearchHit out =
                new SearchHit(
                        hit.itemId(),
                        def.zhName(),
                        hit.method(),
                        hit.x(),
                        hit.y(),
                        hit.z(),
                        hit.dimension(),
                        hit.structureId(),
                        hit.structureZhName(),
                        hit.count(),
                        hit.nth(),
                        instanceIndex,
                        hit.estimated(),
                        elapsed,
                        request.seed(),
                        workNanos);
        state.recordHit(hit.itemId(), hit.count(), left, elapsed);
        listener.onHit(out);
        listener.onProgress(
                new SearchProgress(
                        state.structuresScanned,
                        state.containersReplayed,
                        Map.copyOf(state.foundPerItem),
                        hit.structureId(),
                        elapsed,
                        Map.copyOf(state.elapsedPerItem),
                        Map.copyOf(state.costNanosPerItem),
                        Map.copyOf(state.durationSnapshot(remaining)),
                        Map.copyOf(state.elapsedPerStructure)));
    }

    private void processVaultSequences(
            SearchRequest request,
            Plan plan,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchState state,
            Listener listener) {
        if (!plan.needVault() && !plan.needOminousVault()) {
            return;
        }
        record VaultJob(String table, LootMethod method) {}
        List<VaultJob> jobs = new ArrayList<>();
        if (plan.needVault()) {
            jobs.add(new VaultJob("minecraft:chests/trial_chambers/reward", LootMethod.VAULT));
        }
        if (plan.needOminousVault()) {
            jobs.add(
                    new VaultJob(
                            "minecraft:chests/trial_chambers/reward_ominous",
                            LootMethod.VAULT_OMINOUS));
        }
        for (VaultJob job : jobs) {
            Set<String> wanted = wantedItemsForMethod(request, targets, remaining, job.method());
            if (wanted.isEmpty()) {
                continue;
            }
            int[] anchor = state.vaultAnchor(job.table());
            List<VaultSequencePredictor.OpenResult> hits =
                    VaultSequencePredictor.findHits(
                            oracle,
                            request.seed(),
                            job.table(),
                            job.table(),
                            request.startOffset(),
                            MAX_SEQUENCE_ROLLS,
                            wanted);
            for (VaultSequencePredictor.OpenResult hit : hits) {
                for (LootStack stack : hit.items()) {
                    emitSequenceHit(
                            request,
                            anchor,
                            "minecraft:trial_chambers",
                            stack.itemId(),
                            job.method(),
                            stack.count(),
                            hit.nthOpen(),
                            targets,
                            remaining,
                            state,
                            listener);
                }
            }
            if (hits.isEmpty()) {
                state.warnings.add(
                        job.method().zhName
                                + "序列搜索到 "
                                + MAX_SEQUENCE_ROLLS
                                + " 次仍未命中（已标 ⚠️ 可能超出范围）");
            }
        }
    }

    private void processElderGuardian(
            SearchRequest request,
            Plan plan,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchState state,
            Listener listener) {
        if (!plan.needElderGuardian()) {
            return;
        }
        Set<String> wanted = wantedItemsForMethod(request, targets, remaining, LootMethod.ENTITY);
        int[] anchor = state.monumentAnchorPos;
        for (String targetItemId : wanted) {
            List<ElderGuardianPredictor.KillResult> hits =
                    ElderGuardianPredictor.findHits(
                            oracle,
                            request.seed(),
                            request.lootingLevel(),
                            request.startOffset(),
                            MAX_SEQUENCE_ROLLS,
                            targetItemId);
            for (ElderGuardianPredictor.KillResult hit : hits) {
                emitSequenceHit(
                        request,
                        anchor,
                        "minecraft:monument",
                        targetItemId,
                        LootMethod.ENTITY,
                        1,
                        hit.nthKill(),
                        targets,
                        remaining,
                        state,
                        listener);
            }
            if (hits.isEmpty()) {
                state.warnings.add(
                        "远古守卫者序列搜索到 "
                                + MAX_SEQUENCE_ROLLS
                                + " 次击杀仍未命中 "
                                + targetItemId
                                + "（掠夺 "
                                + request.lootingLevel()
                                + " 级，已标 ⚠️）");
            }
        }
    }

    private void emitSequenceHit(
            SearchRequest request,
            int[] anchorPos,
            String fallbackStructureId,
            String itemId,
            LootMethod method,
            int count,
            int nth,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            SearchState state,
            Listener listener) {
        if (remaining.getOrDefault(itemId, 0) <= 0) {
            return;
        }
        String structureId = fallbackStructureId;
        int x = request.centerX();
        int y = 64;
        int z = request.centerZ();
        boolean estimated = anchorPos == null;
        if (anchorPos != null) {
            x = anchorPos[0];
            y = anchorPos[1];
            z = anchorPos[2];
            structureId = fallbackStructureId;
        }
        StructureSpec spec = versionRegistry.structure(structureId);
        emitHit(
                request,
                new RawHit(
                        itemId,
                        method,
                        x,
                        y,
                        z,
                        spec.dimension(),
                        spec.id(),
                        spec.zhName(),
                        count,
                        nth,
                        estimated),
                1,
                0L,
                targets,
                remaining,
                state,
                listener);
    }

    private Set<String> wantedItemsForMethod(
            SearchRequest request,
            Map<String, ItemDef> targets,
            Map<String, Integer> remaining,
            LootMethod method) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Map.Entry<String, ItemDef> entry : targets.entrySet()) {
            if (remaining.getOrDefault(entry.getKey(), 0) <= 0) {
                continue;
            }
            for (ItemSource source : entry.getValue().sources()) {
                if (source.method().equals(method.id)) {
                    wanted.add(entry.getKey());
                }
            }
        }
        return wanted;
    }

    private static final class SearchState {
        final long startedAt;
        int structuresScanned;
        int containersReplayed;
        int[] monumentAnchorPos;
        final Map<String, Integer> foundPerItem = new LinkedHashMap<>();
        final Map<String, Long> elapsedPerItem = new LinkedHashMap<>();
        final Map<String, Long> costNanosPerItem = new LinkedHashMap<>();
        final Map<String, Long> elapsedPerStructure = new LinkedHashMap<>();
        final Map<String, Integer> instanceCounters = new HashMap<>();
        final Map<String, Integer> failedCounts = new HashMap<>();
        final java.util.Set<String> cappedItems = new java.util.HashSet<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, int[]> vaultAnchors = new HashMap<>();
        final Map<String, Long> itemSpanNanos = new HashMap<>();
        final Map<String, Long> itemClosedNanos = new LinkedHashMap<>();

        SearchState(long startedAt) {
            this.startedAt = startedAt;
        }

        void advanceItem(String itemId, long nanos) {
            itemSpanNanos.merge(itemId, nanos, Long::sum);
        }

        long spanOf(String itemId) {
            return itemSpanNanos.getOrDefault(itemId, 0L);
        }

        void closeSpan(String itemId, boolean countsTowardQuota) {
            if (countsTowardQuota) {
                itemClosedNanos.merge(itemId, spanOf(itemId), Long::sum);
            }
            itemSpanNanos.put(itemId, 0L);
        }

        long displayOf(String itemId) {
            Long closed = itemClosedNanos.get(itemId);
            return closed != null && closed > 0 ? closed : spanOf(itemId);
        }

        Map<String, Long> durationSnapshot(Map<String, Integer> remaining) {
            Map<String, Long> snapshot = new LinkedHashMap<>();
            for (String itemId : remaining.keySet()) {
                snapshot.put(itemId, displayOf(itemId));
            }
            return snapshot;
        }

        void recordVaultSource(String table, int x, int y, int z) {
            vaultAnchors.putIfAbsent(table, new int[] {x, y, z});
        }

        int[] vaultAnchor(String table) {
            return vaultAnchors.get(table);
        }

        int nextInstance(String structureId) {
            return instanceCounters.merge(structureId, 1, Integer::sum);
        }

        void noteItemCapHit(String itemId, String itemZhName, long budgetNanos) {
            if (cappedItems.add(itemId)) {
                warnings.add(itemZhName + " 搜索超时 " + budgetNanos / 1_000_000_000L + "s，可能未搜全（⚠️）");
            }
        }

        void recordHit(String itemId, int count, int left, long elapsed) {
            foundPerItem.merge(itemId, count, Integer::sum);
            if (left <= 0 && !elapsedPerItem.containsKey(itemId)) {
                elapsedPerItem.put(itemId, elapsed);
            }
        }

        void addStructureTime(String structureId, long nanos) {
            if (!structureId.isEmpty()) {
                elapsedPerStructure.merge(structureId, nanos, Long::sum);
            }
        }

        SearchReport report(boolean completed, boolean cancelled, Map<String, Integer> remaining) {
            long total = System.nanoTime() - startedAt;
            for (String itemId : foundPerItem.keySet()) {
                elapsedPerItem.putIfAbsent(itemId, total);
            }
            for (var failed : failedCounts.entrySet()) {
                warnings.add(
                        failed.getKey() + " 有 " + failed.getValue() + " 处生成/扫描失败（已跳过，耗时仍计入预算）");
            }
            return new SearchReport(
                    completed,
                    cancelled,
                    Map.copyOf(foundPerItem),
                    Map.copyOf(elapsedPerItem),
                    Map.copyOf(costNanosPerItem),
                    Map.copyOf(elapsedPerStructure),
                    total,
                    structuresScanned,
                    containersReplayed,
                    List.copyOf(warnings),
                    Map.copyOf(durationSnapshot(remaining)));
        }
    }
}
