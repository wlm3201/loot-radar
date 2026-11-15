package com.lootradar.dump;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

/**
 * 结构源普查真值：按结构集枚举起点 → 生成目标区块到 FULL 状态 → 抓取容器内容，
 * 分 tick 推进并周期性重写 {@code loot-radar-source-truth/1}，完成后自动停服。
 * 通过 {@code -Dlootradar.source*} 系统属性配置半径、维度、批次与输出路径。
 */
final class SourceCoverageDump {
    private static SourceCoverageDump active;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final String mode = "sources";
    private final String dimension =
            "minecraft:" + System.getProperty("lootradar.sourceDimension", "overworld");
    private final Set<String> wantedStructures = new HashSet<>();
    private final List<Map<String, Object>> starts = new ArrayList<>();
    private final int radius = Integer.getInteger("lootradar.sourceRadius", 5000);
    private final int centerX = Integer.getInteger("lootradar.sourceX", 0);
    private final int centerZ = Integer.getInteger("lootradar.sourceZ", 0);
    private final int batch = Integer.getInteger("lootradar.sourceBatch", 16);
    private final boolean parallelFeatures = Boolean.getBoolean("lootradar.sourceParallelFeatures");
    private final boolean prefetchTerrain = Boolean.getBoolean("lootradar.sourcePrefetchTerrain");
    private final ExecutorService requests =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("room-census-request").factory());
    private final Path output = Path.of(System.getProperty("lootradar.sourceOut", "truth.json"));
    private final long started = System.nanoTime();
    private final List<ChunkPos> candidates = new ArrayList<>();
    private final Set<Long> captureKeys = new HashSet<>();
    private final Set<Long> generatedKeys = new HashSet<>();
    private final Set<Long> capturedKeys = new HashSet<>();
    private final List<Map<String, Object>> containers = new ArrayList<>();
    private final Set<BlockPos> seen = new HashSet<>();
    private final List<ChunkPos> wellAttempts = new ArrayList<>();
    private final int perTypeCap = Integer.getInteger("lootradar.sourcePerTypeCap", 100);
    private final Map<String, Integer> totalStartsByType = new LinkedHashMap<>();
    private final Map<String, Integer> starts5000ByType = new LinkedHashMap<>();
    private final Map<String, Integer> usedStartsByType = new LinkedHashMap<>();
    private final Map<String, Integer> chunksByType = new LinkedHashMap<>();
    private final Map<String, Long> nanosByType = new LinkedHashMap<>();
    private final Map<String, List<int[]>> boxesByType = new LinkedHashMap<>();

    private record TypeGroup(
            String id, int totalStarts, int usedStarts, List<ChunkPos> captureChunks) {}

    private List<TypeGroup> groups = List.of();
    private int candidateIndex, validStarts, chunkIndex, fullChunks;
    private int groupIndex;
    private TypeGroup currentGroup;
    private long groupStarted;
    private List<ChunkPos> targets = List.of();
    private long generationNanos, captureNanos, lastProgress;
    private String phase = "structure_starts";

    static void register() {
        if (System.getProperty("lootradar.sourceRadius") == null) return;
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> {
                    try {
                        active = new SourceCoverageDump(server);
                    } catch (Throwable error) {
                        fail(server, error);
                    }
                });
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    if (active == null) return;
                    try {
                        active.tick();
                    } catch (Throwable error) {
                        fail(server, error);
                    }
                });
    }

    private static void fail(MinecraftServer server, Throwable error) {
        LootRadarDump.LOGGER.error("SOURCE_COVERAGE_FAILED", error);
        if (active != null) {
            active.phase = "failed";
            try {
                active.write(false, error.toString());
            } catch (Exception ignored) {
            }
            active.requests.shutdownNow();
        }
        active = null;
        server.execute(() -> server.halt(false));
    }

    private SourceCoverageDump(MinecraftServer server) {
        this.server = server;
        this.level =
                server.getLevel(
                        ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
        if (radius < 1 || batch < 1)
            throw new IllegalArgumentException("radius/batch must be positive");
        if (parallelFeatures && prefetchTerrain)
            throw new IllegalArgumentException(
                    "Choose either parallel features or terrain prefetch");
        var state = level.getChunkSource().getGeneratorState();
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        var overrideSets = System.getProperty("lootradar.sourceSets");
        var setNames =
                overrideSets != null && !overrideSets.isBlank()
                        ? java.util.Arrays.stream(overrideSets.split(","))
                                .map(String::trim).toList()
                        : switch (dimension) {
                            case "minecraft:the_nether" -> List.of("nether_complexes", "ruined_portals");
                            case "minecraft:the_end" -> List.of("end_cities");
                            default ->
                                    List.of(
                                            "ancient_cities",
                                            "buried_treasures",
                                            "desert_pyramids",
                                            "jungle_temples",
                                            "ocean_monuments",
                                            "ocean_ruins",
                                            "pillager_outposts",
                                            "ruined_portals",
                                            "shipwrecks",
                                            "strongholds",
                                            "trail_ruins",
                                            "trial_chambers",
                                            "villages",
                                            "woodland_mansions");
                        };
        Set<ChunkPos> unique = new HashSet<>();
        int bound = (radius + 527) / 16;
        for (String name : setNames) {
            var set =
                    registry.getValueOrThrow(
                            ResourceKey.create(
                                    Registries.STRUCTURE_SET,
                                    Identifier.parse("minecraft:" + name)));
            for (var member : set.structures())
                wantedStructures.add(
                        member.structure().unwrapKey().orElseThrow().identifier().toString());
            if (set.placement() instanceof ConcentricRingsStructurePlacement rings) {
                for (var pos : state.getRingPositionsFor(rings))
                    if (intersectsCircle(pos, radius + 512)) unique.add(pos);
            } else if (set.placement()
                    instanceof
                    net.minecraft.world.level.levelgen.structure.placement
                                    .RandomSpreadStructurePlacement
                            spread) {
                int spacing = spread.spacing();
                for (int gx = Math.floorDiv(-bound, spacing);
                        gx <= Math.floorDiv(bound, spacing);
                        gx++)
                    for (int gz = Math.floorDiv(-bound, spacing);
                            gz <= Math.floorDiv(bound, spacing);
                            gz++) {
                        var pos =
                                spread.getPotentialStructureChunk(
                                        level.getSeed(), gx * spacing, gz * spacing);
                        if (intersectsCircle(pos, radius + 512)
                                && spread.isStructureChunk(state, pos.x(), pos.z()))
                            unique.add(pos);
                    }
            }
        }
        candidates.addAll(unique);
        candidates.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        if (dimension.equals("minecraft:overworld")) {
            var generator = level.getChunkSource().getGenerator();
            var feature =
                    level.registryAccess()
                            .lookupOrThrow(Registries.PLACED_FEATURE)
                            .getValueOrThrow(
                                    ResourceKey.create(
                                            Registries.PLACED_FEATURE,
                                            Identifier.parse("minecraft:desert_well")));
            var steps =
                    net.minecraft.world.level.biome.FeatureSorter.buildFeaturesPerStep(
                            List.copyOf(generator.getBiomeSource().possibleBiomes()),
                            b -> b.value().getGenerationSettings().features(),
                            true);
            int featureStep = -1, featureIndex = -1;
            for (int step = 0; step < steps.size(); step++) {
                int index = steps.get(step).features().indexOf(feature);
                if (index >= 0) {
                    featureStep = step;
                    featureIndex = index;
                    break;
                }
            }
            if (featureStep < 0)
                throw new IllegalStateException("Missing native desert well feature");
            int wellBound = (radius + 31) / 16;
            for (int x = -wellBound; x <= wellBound; x++)
                for (int z = -wellBound; z <= wellBound; z++) {
                    var pos = new ChunkPos(x, z);
                    if (!intersectsCircle(pos, radius + 16)) continue;
                    if (!level.getBiome(new BlockPos(x * 16 + 8, 64, z * 16 + 8))
                            .is(net.minecraft.world.level.biome.Biomes.DESERT)) continue;
                    var random =
                            new net.minecraft.world.level.levelgen.WorldgenRandom(
                                    new net.minecraft.world.level.levelgen.XoroshiroRandomSource(
                                            0));
                    long decorationSeed = random.setDecorationSeed(level.getSeed(), x * 16, z * 16);
                    random.setFeatureSeed(decorationSeed, featureIndex, featureStep);
                    if (random.nextFloat() >= 1.0f / 1000) continue;
                    wellAttempts.add(new ChunkPos(x, z));
                }
        }
        progress();
    }

    private boolean inCircle(int x, int z) {
        long dx = (long) x - centerX, dz = (long) z - centerZ;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private boolean intersectsCircle(ChunkPos pos, int range) {
        long x = Math.clamp(centerX, pos.getMinBlockX(), pos.getMaxBlockX());
        long z = Math.clamp(centerZ, pos.getMinBlockZ(), pos.getMaxBlockZ());
        long dx = x - centerX, dz = z - centerZ;
        return dx * dx + dz * dz <= (long) range * range;
    }

    private void prepareTargets() {
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (var start : starts) {
            byType.computeIfAbsent((String) start.get("structure"), k -> new ArrayList<>()).add(start);
        }
        List<TypeGroup> built = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            String id = entry.getKey();
            var list = entry.getValue();
            list.sort(Comparator.comparingLong(SourceCoverageDump::centerDistanceSq));
            int total = list.size();
            int used = Math.min(perTypeCap, total);
            List<ChunkPos> capture = new ArrayList<>();
            var boxes = new ArrayList<int[]>();
            for (int i = 0; i < used; i++) {
                int[] box = ((List<Integer>) list.get(i).get("box")).stream().mapToInt(Integer::intValue).toArray();
                boxes.add(box);
                for (int x = box[0] >> 4; x <= box[3] >> 4; x++) {
                    for (int z = box[2] >> 4; z <= box[5] >> 4; z++) {
                        var target = new ChunkPos(x, z);
                        if (intersectsCircle(target, radius)
                                && !captureKeys.contains(target.pack())) {
                            captureKeys.add(target.pack());
                            capture.add(target);
                        }
                    }
                }
            }
            int within5000 = 0;
            for (var s : list) {
                if (centerDistanceSq(s) <= 5000L * 5000L) within5000++;
            }
            totalStartsByType.put(id, total);
            starts5000ByType.put(id, within5000);
            usedStartsByType.put(id, used);
            boxesByType.put(id, boxes);
            built.add(new TypeGroup(id, total, used, capture));
        }
        if (!wellAttempts.isEmpty()) {
            wellAttempts.sort(Comparator.comparingInt(c -> (int) Math.min(Integer.MAX_VALUE,
                    (long) c.getMinBlockX() * c.getMinBlockX() + (long) c.getMinBlockZ() * c.getMinBlockZ())));
            int total = wellAttempts.size();
            int used = Math.min(perTypeCap, total);
            List<ChunkPos> capture = new ArrayList<>();
            for (int i = 0; i < used; i++) {
                var attempt = wellAttempts.get(i);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        var neighbor = new ChunkPos(attempt.x() + dx, attempt.z() + dz);
                        if (intersectsCircle(neighbor, radius)
                                && !captureKeys.contains(neighbor.pack())) {
                            captureKeys.add(neighbor.pack());
                            capture.add(neighbor);
                        }
                    }
                }
            }
            totalStartsByType.put("minecraft:desert_well", total);
            starts5000ByType.put(
                    "minecraft:desert_well",
                    (int) wellAttempts.stream()
                            .filter(c -> (long) c.getMinBlockX() * c.getMinBlockX()
                                            + (long) c.getMinBlockZ() * c.getMinBlockZ()
                                    <= 5000L * 5000L)
                            .count());
            usedStartsByType.put("minecraft:desert_well", used);
            built.add(new TypeGroup("minecraft:desert_well", total, used, capture));
        }
        groups = built;
        List<Map<String, Object>> used = new ArrayList<>();
        for (TypeGroup group : built) {
            var list = byType.get(group.id());
            if (list != null) used.addAll(list.subList(0, group.usedStarts()));
        }
        starts.clear();
        starts.addAll(used);
        phase = "features";
    }

    private static long centerDistanceSq(Map<String, Object> start) {
        List<Integer> chunk = (List<Integer>) start.get("chunk");
        long x = chunk.get(0) * 16L + 8, z = chunk.get(1) * 16L + 8;
        return x * x + z * z;
    }

    private void tick() throws Exception {
        if (phase.equals("structure_starts")) {
            for (int n = 0; n < batch && candidateIndex < candidates.size(); n++) {
                var pos = candidates.get(candidateIndex++);
                var chunk = level.getChunk(pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS, true);
                for (var entry : chunk.getAllStarts().entrySet()) {
                    var id =
                            level.registryAccess()
                                    .lookupOrThrow(Registries.STRUCTURE)
                                    .getKey(entry.getKey());
                    if (id == null
                            || !wantedStructures.contains(id.toString())
                            || !VersionCompat.isValidStart(entry.getValue())) continue;
                    var box = entry.getValue().getBoundingBox();
                    long nearX = Math.clamp(centerX, box.minX(), box.maxX()),
                            nearZ = Math.clamp(centerZ, box.minZ(), box.maxZ());
                    if ((nearX - centerX) * (nearX - centerX)
                                    + (nearZ - centerZ) * (nearZ - centerZ)
                            > (long) radius * radius) continue;
                    if (Math.max(
                                            Math.abs(box.minX() - pos.getMinBlockX()),
                                            Math.abs(box.maxX() - pos.getMinBlockX()))
                                    > 496
                            || Math.max(
                                            Math.abs(box.minZ() - pos.getMinBlockZ()),
                                            Math.abs(box.maxZ() - pos.getMinBlockZ()))
                                    > 496)
                        throw new IllegalStateException(
                                "Structure exceeds census root margin: " + id);
                    validStarts++;
                    starts.add(
                            Map.of(
                                    "structure",
                                    id.toString(),
                                    "chunk",
                                    List.of(pos.x(), pos.z()),
                                    "box",
                                    List.of(
                                            box.minX(),
                                            box.minY(),
                                            box.minZ(),
                                            box.maxX(),
                                            box.maxY(),
                                            box.maxZ())));
                }
            }
            if (candidateIndex == candidates.size()) prepareTargets();
        }
        if (phase.equals("features")) {
            if (currentGroup == null) {
                if (groupIndex >= groups.size()) {
                    if (!capturedKeys.equals(captureKeys))
                        throw new IllegalStateException("Incomplete finalized chunk capture");
                    phase = "complete";
                    write(true, "");
                    LootRadarDump.LOGGER.info("SOURCE_COVERAGE_COMPLETE {}", output.toAbsolutePath());
                    requests.shutdown();
                    active = null;
                    server.execute(() -> server.halt(false));
                    return;
                }
                currentGroup = groups.get(groupIndex++);
                Set<Long> all = new HashSet<>();
                for (var pos : currentGroup.captureChunks()) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++)
                            all.add(ChunkPos.pack(pos.x() + dx, pos.z() + dz));
                    }
                }
                targets =
                        all.stream()
                                .map(ChunkPos::unpack)
                                .sorted(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z))
                                .toList();
                chunkIndex = 0;
                groupStarted = System.nanoTime();
            }
            for (int n = 0; n < batch && chunkIndex < targets.size(); n++) {
                var pos = targets.get(chunkIndex++);
                long began = System.nanoTime();
                level.getChunk(pos.x(), pos.z(), ChunkStatus.FEATURES, true);
                generationNanos += System.nanoTime() - began;
                generatedKeys.add(pos.pack());
                chunksByType.merge(currentGroup.id(), 1, Integer::sum);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++)
                        maybeCapture(new ChunkPos(pos.x() + dx, pos.z() + dz));
                }
            }
            if (chunkIndex == targets.size()) {
                nanosByType.merge(currentGroup.id(), System.nanoTime() - groupStarted, Long::sum);
                currentGroup = null;
            }
        }
        if (System.nanoTime() - lastProgress > 10_000_000_000L) progress();
    }

    private void generateParallelBatch() {
        var ready = requestNativeBatch(ChunkStatus.FEATURES);
        for (var pos : ready) generatedKeys.add(pos.pack());
        for (var pos : ready) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++)
                    maybeCapture(new ChunkPos(pos.x() + dx, pos.z() + dz));
            }
        }
        chunkIndex += ready.size();
    }

    private List<ChunkPos> requestNativeBatch(ChunkStatus status) {
        var ready = targets.subList(chunkIndex, Math.min(chunkIndex + batch, targets.size()));
        long began = System.nanoTime();
        var source = level.getChunkSource();
        var futures =
                ready.stream()
                        .map(
                                pos ->
                                        CompletableFuture.supplyAsync(
                                                        () ->
                                                                source.getChunkFuture(
                                                                        pos.x(), pos.z(), status,
                                                                        true),
                                                        requests)
                                                .thenCompose(future -> future))
                        .toList();
        var all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        while (!all.isDone()) {
            if (!source.pollTask()) LockSupport.parkNanos(100_000L);
        }
        all.join();
        for (var future : futures) {
            if (future.join().orElse(null) == null)
                throw new IllegalStateException("Native chunk future returned an unloaded chunk");
        }
        generationNanos += System.nanoTime() - began;
        return ready;
    }

    private void maybeCapture(ChunkPos pos) {
        long key = pos.pack();
        if (!captureKeys.contains(key) || capturedKeys.contains(key)) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!generatedKeys.contains(ChunkPos.pack(pos.x() + dx, pos.z() + dz))) return;
            }
        }
        long began = System.nanoTime();
        var full = level.getChunk(pos.x(), pos.z());
        fullChunks++;
        List<BlockPos> matches = List.copyOf(full.getBlockEntitiesPos());
        if (!matches.isEmpty()) {
            for (var blockPos : matches) {
                if (!inCircle(blockPos.getX(), blockPos.getZ())) continue;
                var entity = full.getBlockEntity(blockPos);
                if (entity == null)
                    continue;
                Map<String, Object> row = ContainerCollector.snapshot(level, blockPos, entity);
                if (row == null) continue;
                if (!seen.add(blockPos))
                    throw new IllegalStateException("Duplicate source " + blockPos);
                Map<String, Integer> items = new TreeMap<>();
                if (entity
                        instanceof
                        net.minecraft.world.level.block.entity.BrushableBlockEntity brushable) {
                    var user =
                            (net.minecraft.world.entity.LivingEntity)
                                    BuiltInRegistries.ENTITY_TYPE
                                            .getValue(Identifier.parse("minecraft:armor_stand"))
                                            .create(
                                                    level,
                                                    net.minecraft.world.entity.EntitySpawnReason
                                                            .COMMAND);
                    brushable.brush(
                            level.getGameTime(),
                            level,
                            user,
                            net.minecraft.core.Direction.UP,
                            new net.minecraft.world.item.ItemStack(
                                    net.minecraft.world.item.Items.BRUSH));
                    var stack = brushable.getItem();
                    if (!stack.isEmpty())
                        items.merge(
                                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                stack.getCount(),
                                Integer::sum);
                } else if (entity instanceof RandomizableContainer inventory) {
                    for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                        var stack = inventory.getItem(slot);
                        if (!stack.isEmpty())
                            items.merge(
                                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                    stack.getCount(),
                                    Integer::sum);
                    }
                }
                if (row.get("kind").equals("pot_sherd")) {
                    var pot =
                            (net.minecraft.world.level.block.entity.DecoratedPotBlockEntity) entity;
                    for (var item : PotItems.ordered(pot.getDecorations()))
                        items.merge(
                                BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
                }
                row.put("items", items);
                if (row.containsKey("seed"))
                    row.put("lootSeed", String.valueOf(row.remove("seed")));
                row.put("group", currentGroup == null ? "" : currentGroup.id());
                containers.add(row);
            }
        }
        capturedKeys.add(key);
        captureNanos += System.nanoTime() - began;
    }

    private void progress() {
        lastProgress = System.nanoTime();
        LootRadarDump.LOGGER.info(
                "SOURCE_COVERAGE {} {} starts {}/{} chunks {}/{} captured {}/{} chests {}",
                mode,
                phase,
                candidateIndex,
                candidates.size(),
                chunkIndex,
                targets.size(),
                capturedKeys.size(),
                captureKeys.size(),
                containers.size());
        try {
            write(false, "");
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void write(boolean complete, String error) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "loot-radar-source-truth/1");
        root.put("dimension", dimension);
        root.put("starts", starts);
        root.put("captureImplementation", "native_source_full_chunk_open_and_brush/2");
        root.put("verificationScope", "complete_structural_sources_and_well_attempts");
        root.put("version", SharedConstants.getCurrentVersion().id());
        root.put("seed", Long.toString(level.getSeed()));
        root.put("center", List.of(centerX, centerZ));
        root.put("radius", radius);
        root.put("mode", mode);
        root.put("complete", complete);
        root.put("phase", phase);
        root.put("error", error);
        root.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
        root.put("generationSeconds", generationNanos / 1e9);
        root.put("captureSeconds", captureNanos / 1e9);
        root.put("parallelFeatures", parallelFeatures);
        root.put("prefetchTerrain", prefetchTerrain);
        root.put("batchSize", batch);
        root.put(
                "generationStatus",
                "FEATURES halo, then FULL for every capture chunk before enumerating sources");
        root.put(
                "generationOrder",
                parallelFeatures
                        ? "batches submitted X ascending then Z ascending; native workers complete concurrently; capture after surrounding 3x3 FEATURES"
                        : "chunk X ascending, then Z ascending; capture after surrounding 3x3 FEATURES");
        root.put("candidateStarts", candidates.size());
        root.put("perTypeCap", perTypeCap);
        root.put("totalStartsByType", totalStartsByType);
        root.put("starts5000ByType", starts5000ByType);
        root.put("usedStartsByType", usedStartsByType);
        Map<String, Map<String, Object>> perStructure = new LinkedHashMap<>();
        for (TypeGroup group : groups) {
            var stat = new LinkedHashMap<String, Object>();
            stat.put("totalStarts", group.totalStarts());
            stat.put("usedStarts", group.usedStarts());
            stat.put("chunksGenerated", chunksByType.getOrDefault(group.id(), 0));
            stat.put("seconds", nanosByType.getOrDefault(group.id(), 0L) / 1e9);
            var boxes = boxesByType.getOrDefault(group.id(), List.of());
            int count = 0;
            for (var row : containers) {
                boolean inside = false;
                if (!boxes.isEmpty()) {
                    var pos = (List<?>) row.get("pos");
                    int x = (Integer) pos.get(0), y = (Integer) pos.get(1), z = (Integer) pos.get(2);
                    for (int[] box : boxes) {
                        if (x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4]
                                && z >= box[2] && z <= box[5]) {
                            inside = true;
                            break;
                        }
                    }
                } else {
                    inside = group.id().equals(row.get("group"));
                }
                if (inside) count++;
            }
            stat.put("containers", count);
            perStructure.put(group.id(), stat);
        }
        root.put("perStructure", perStructure);
        root.put("startsChecked", candidateIndex);
        root.put("validStarts", validStarts);
        root.put("targetChunks", targets.size());
        root.put("featuresChunksGenerated", chunkIndex);
        root.put("captureChunks", captureKeys.size());
        root.put("chunksCaptured", capturedKeys.size());
        root.put("fullChestChunks", fullChunks);
        root.put("containers", containers);
        Files.createDirectories(output.toAbsolutePath().getParent());
        var temp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                Files.move(temp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (java.io.IOException failure) {
                last = failure;
                try {
                    Thread.sleep(250);
                } catch (java.lang.InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw last;
    }
}
