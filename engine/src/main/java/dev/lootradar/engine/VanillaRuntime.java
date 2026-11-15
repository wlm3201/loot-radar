package dev.lootradar.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * 一个进程内的「原生 Minecraft 世界生成运行时」：加载注册表/数据包、构建三个维度的 ChunkGenerator 与
 * RandomState，并提供结构生成、地形高度、生物群系查询能力。
 *
 * <p>实例按世界种子缓存复用（{@link #load(long)} 代价较高）；{@code close()} 会释放 临时模板目录。所有可能并发生成地形的地方都通过 {@link
 * NativeTerrainCache} 串行化。
 */
public final class VanillaRuntime implements AutoCloseable {

    public record DecorationCoordinates(int step, int indexWithinStep) {}

    private record DimensionContext(
            ResourceKey<Level> levelKey,
            ChunkGenerator chunkGenerator,
            RandomState randomState,
            net.minecraft.world.level.biome.Climate.Sampler climateSampler,
            LevelHeightAccessor heightAccessor,
            DimensionType dimensionType,
            ChunkGeneratorStructureState structureState) {}

    private final long worldSeed;
    private final CloseableResourceManager resourceManager;
    private final ReloadableServerResources reloadableResources;
    private final RegistryAccess.Frozen registries;
    private final WorldDimensions.Complete dimensions;
    private final LevelStorageSource.LevelStorageAccess storageAccess;
    private final Path scratchPath;
    private final StructureTemplateManager templateManager;
    private final Map<String, DimensionContext> dimensionContexts;
    private final SuppressingServerLevel suppressingServerLevel;
    private final Map<String, SuppressingServerLevel> contextualLevels;
    private final Map<String, MineshaftReferences> mineshaftReferences =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, NativeTerrainCache> terrainCaches =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Map<Long, Holder<Biome>>> carverBiomes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.LinkedHashMap<ChunkPos, java.util.Set<Holder<Biome>>>>
            biomePalettes = new java.util.concurrent.ConcurrentHashMap<>();

    java.util.Set<Holder<Biome>> biomePalette(
            String dimension,
            ChunkPos position,
            java.util.function.Supplier<java.util.Set<Holder<Biome>>> factory) {
        String normalized =
                dimension.startsWith("minecraft:") ? dimension : "minecraft:" + dimension;
        var cache =
                biomePalettes.computeIfAbsent(
                        normalized, ignored -> new java.util.LinkedHashMap<>(16, 0.75f, true));
        synchronized (cache) {
            var existing = cache.get(position);
            if (existing != null) return existing;
        }
        var generated = java.util.Set.copyOf(factory.get());
        synchronized (cache) {
            var existing = cache.get(position);
            if (existing != null) return existing;
            cache.put(position, generated);
            while (cache.size() > 1024) cache.pollFirstEntry();
        }
        return generated;
    }

    public static boolean isValidStart(
            net.minecraft.world.level.levelgen.structure.StructureStart start) {
        return MinecraftVersionAdapter.isValidStart(start);
    }

    MineshaftReferences mineshaftReferences(String dimension) {
        return mineshaftReferences.computeIfAbsent(
                dimension, key -> new MineshaftReferences(this, key));
    }

    NativeTerrainCache terrainCache(String dimension) {
        return terrainCaches.computeIfAbsent(dimension, key -> new NativeTerrainCache(512));
    }

    net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
            structurePieceContext() {
        return MinecraftVersionAdapter.serializationContext(
                resourceManager, registries, templateManager);
    }

    Holder<Biome> carverBiome(String dimension, ChunkPos source) {
        return carverBiomes
                .computeIfAbsent(dimension, key -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(
                        ChunkPos.pack(source.x(), source.z()),
                        key ->
                                MinecraftVersionAdapter.noiseBiome(
                                        this,
                                        dimension,
                                        source.getMinBlockX() >> 2,
                                        0,
                                        source.getMinBlockZ() >> 2));
    }

    private VanillaRuntime(
            long worldSeed,
            CloseableResourceManager resourceManager,
            ReloadableServerResources reloadableResources,
            LayeredRegistryAccess<RegistryLayer> layeredRegistries,
            WorldDimensions.Complete dimensions) {
        this.worldSeed = worldSeed;
        this.resourceManager = resourceManager;
        this.reloadableResources = reloadableResources;
        this.registries = layeredRegistries.compositeAccess();
        this.dimensions = dimensions;
        StubMinecraftServer stubServer =
                StubMinecraftServer.create(reloadableResources.fullRegistries());
        this.suppressingServerLevel =
                SuppressingServerLevel.create(worldSeed, stubServer, this.registries);
        this.dimensionContexts =
                Map.of(
                        "minecraft:overworld",
                                createDimensionContext(Level.OVERWORLD, LevelStem.OVERWORLD),
                        "minecraft:the_nether",
                                createDimensionContext(Level.NETHER, LevelStem.NETHER),
                        "minecraft:the_end", createDimensionContext(Level.END, LevelStem.END));
        this.contextualLevels = new java.util.HashMap<>();
        for (String dimensionId : dimensionContexts.keySet()) {
            this.contextualLevels.put(
                    dimensionId,
                    SuppressingServerLevel.createContextual(this, dimensionId, stubServer));
        }

        Path createdScratch = null;
        try {
            createdScratch = Files.createTempDirectory("loot-radar-templates-");
            LevelStorageSource storageSource = LevelStorageSource.createDefault(createdScratch);
            this.storageAccess = storageSource.createAccess("scratch");
            this.scratchPath = createdScratch;
        } catch (IOException exception) {
            if (createdScratch != null) {
                try {
                    deleteRecursively(createdScratch);
                } catch (IOException cleanupException) {
                    exception.addSuppressed(cleanupException);
                }
            }
            throw new UncheckedIOException(
                    "Could not create the temporary template path", exception);
        }
        this.templateManager =
                new ThreadLocalTemplates(
                        resourceManager,
                        storageAccess,
                        DataFixers.getDataFixer(),
                        registries.lookupOrThrow(Registries.BLOCK));
        stubServer.setTemplates(templateManager);
    }

    // 每次 load 都会 bootstrap 原版并加载数据包，代价较高；调用方按世界种子复用实例。
    public static VanillaRuntime load(long worldSeed) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        WorldLoader.PackConfig packConfig =
                new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, true, true);
        WorldLoader.InitConfig initConfig =
                new WorldLoader.InitConfig(
                        packConfig,
                        Commands.CommandSelection.DEDICATED,
                        PermissionSet.NO_PERMISSIONS);

        try {
            return WorldLoader.load(
                            initConfig,
                            context -> {
                                Registry<LevelStem> datapackDimensions =
                                        context.datapackDimensions()
                                                .lookupOrThrow(Registries.LEVEL_STEM);
                                WorldDimensions normal =
                                        WorldPresets.createNormalWorldDimensions(
                                                MinecraftVersionAdapter.worldgen(context));
                                WorldDimensions.Complete complete = normal.bake(datapackDimensions);
                                return new WorldLoader.DataLoadOutput<>(
                                        complete, complete.dimensionsRegistryAccess());
                            },
                            (resources, reloadable, registries, complete) ->
                                    new VanillaRuntime(
                                            worldSeed, resources, reloadable, registries, complete),
                            Util.backgroundExecutor(),
                            Runnable::run)
                    .join();
        } catch (CompletionException exception) {
            throw new IllegalStateException(
                    "Could not load vanilla worldgen data", exception.getCause());
        }
    }

    private DimensionContext createDimensionContext(
            ResourceKey<Level> levelKey, ResourceKey<LevelStem> stemKey) {
        LevelStem stem = dimensions.dimensions().getValueOrThrow(stemKey);
        ChunkGenerator generator = stem.generator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            throw new IllegalStateException(levelKey.identifier() + " is not noise-based");
        }
        RandomState state =
                MinecraftVersionAdapter.randomState(
                        registries, noiseGenerator.generatorSettings(), worldSeed);
        DimensionType type = stem.type().value();
        ChunkGeneratorStructureState structureState =
                generator.createState(
                        registries.lookupOrThrow(Registries.STRUCTURE_SET), state, worldSeed);
        return new DimensionContext(
                levelKey,
                generator,
                state,
                MinecraftVersionAdapter.climate(state),
                LevelHeightAccessor.create(type.minY(), type.height()),
                type,
                structureState);
    }

    public long worldSeed() {
        return worldSeed;
    }

    public RegistryAccess.Frozen registries() {
        return registries;
    }

    public ReloadableServerResources reloadableResources() {
        return reloadableResources;
    }

    public StructureTemplateManager templateManager() {
        return templateManager;
    }

    public net.minecraft.server.level.ServerLevel suppressingServerLevel() {
        return suppressingServerLevel;
    }

    public net.minecraft.server.level.ServerLevel contextualLevel(String dimensionId) {
        return contextualLevels.get(levelKey(dimensionId).identifier().toString());
    }

    public Holder<Biome> biomeAt(String dimensionId, net.minecraft.core.BlockPos pos) {
        return contextualLevel(dimensionId).getBiome(pos);
    }

    public ChunkGenerator chunkGenerator(String dimensionId) {
        return context(dimensionId).chunkGenerator();
    }

    public RandomState randomState(String dimensionId) {
        return context(dimensionId).randomState();
    }

    public net.minecraft.world.level.biome.Climate.Sampler climateSampler(String dimensionId) {
        return context(dimensionId).climateSampler();
    }

    public LevelHeightAccessor heightAccessor(String dimensionId) {
        return context(dimensionId).heightAccessor();
    }

    public DimensionType dimensionType(String dimensionId) {
        return context(dimensionId).dimensionType();
    }

    public ResourceKey<Level> levelKey(String dimensionId) {
        return context(dimensionId).levelKey();
    }

    private DimensionContext context(String dimensionId) {
        String normalized =
                dimensionId.startsWith("minecraft:") ? dimensionId : "minecraft:" + dimensionId;
        DimensionContext context = dimensionContexts.get(normalized);
        if (context == null) {
            throw new IllegalArgumentException("unknown dimension: " + dimensionId);
        }
        return context;
    }

    public int surfaceHeight(String dimensionId, int blockX, int blockZ) {
        DimensionContext context = context(dimensionId);
        return MinecraftVersionAdapter.firstFreeHeight(
                context.chunkGenerator(),
                blockX,
                blockZ,
                Heightmap.Types.MOTION_BLOCKING,
                context.heightAccessor(),
                context.randomState());
    }

    public DecorationCoordinates structureDecorationCoordinates(
            String structureId, dev.lootradar.core.structure.StructureSpec spec) {
        if (spec != null && spec.decorationIndex() != null && spec.generationStep() != null) {
            return new DecorationCoordinates(spec.generationStep(), spec.decorationIndex());
        }
        return structureDecorationCoordinates(structureId);
    }

    // 未钉死索引时，按原生注册表的遍历顺序推算 (step, index)；跨版本顺序变化时
    // 必须用 data/<version>/structures.json 中校准过的值覆盖，见 StructureSpec。
    public DecorationCoordinates structureDecorationCoordinates(String structureId) {
        Registry<Structure> structureRegistry = registries.lookupOrThrow(Registries.STRUCTURE);
        ResourceKey<Structure> key =
                ResourceKey.create(Registries.STRUCTURE, Identifier.parse(structureId));
        Structure target = structureRegistry.getValueOrThrow(key);
        int step = target.step().ordinal();
        List<Structure> structuresInStep =
                structureRegistry.stream()
                        .filter(structure -> structure.step() == target.step())
                        .toList();
        int index = structuresInStep.indexOf(target);
        return new DecorationCoordinates(step, index);
    }

    public String structureId(StructureStart start) {
        return registries
                .lookupOrThrow(Registries.STRUCTURE)
                .getKey(start.getStructure())
                .toString();
    }

    public StructureStart generateStructure(
            String structureId, String dimensionId, ChunkPos startChunk) {
        Registry<Structure> structureRegistry = registries.lookupOrThrow(Registries.STRUCTURE);
        ResourceKey<Structure> key =
                ResourceKey.create(Registries.STRUCTURE, Identifier.parse(structureId));
        Holder.Reference<Structure> holder =
                structureRegistry
                        .get(key)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown structure: " + structureId));
        if (structureId.equals("minecraft:stronghold")) {
            synchronized (
                    net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces
                            .class) {
                return MinecraftVersionAdapter.generate(holder, this, dimensionId, startChunk);
            }
        }
        return MinecraftVersionAdapter.generate(holder, this, dimensionId, startChunk);
    }

    public StructureStart generateSelectedStructure(
            List<StructureSetMember> members, String dimensionId, ChunkPos startChunk) {
        if (members.size() == 1) {
            return generateStructure(members.getFirst().id(), dimensionId, startChunk);
        }
        List<StructureSetMember> remaining = new ArrayList<>(members);
        WorldgenRandom selectionRandom = new WorldgenRandom(new LegacyRandomSource(0L));
        selectionRandom.setLargeFeatureSeed(worldSeed, startChunk.x(), startChunk.z());
        int totalWeight = remaining.stream().mapToInt(StructureSetMember::weight).sum();
        while (!remaining.isEmpty()) {
            int choice = selectionRandom.nextInt(totalWeight);
            int selectedIndex = 0;
            for (; selectedIndex < remaining.size(); selectedIndex++) {
                choice -= remaining.get(selectedIndex).weight();
                if (choice < 0) {
                    break;
                }
            }
            StructureSetMember selected = remaining.get(selectedIndex);
            StructureStart start = generateStructure(selected.id(), dimensionId, startChunk);
            if (MinecraftVersionAdapter.isValidStart(start)) {
                return start;
            }
            remaining.remove(selectedIndex);
            totalWeight -= selected.weight();
        }
        return MinecraftVersionAdapter.invalidStart();
    }

    public record StructureSetMember(String id, int weight) {}

    public record FeatureCoordinates(int step, int indexWithinStep) {}

    private final Map<String, FeatureCoordinates> featureCoordinateCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData>>
            featureSteps = new java.util.concurrent.ConcurrentHashMap<>();

    List<net.minecraft.world.level.biome.FeatureSorter.StepFeatureData> featuresPerStep(
            String dimension) {
        return featureSteps.computeIfAbsent(
                dimension,
                key ->
                        net.minecraft.world.level.biome.FeatureSorter.buildFeaturesPerStep(
                                List.copyOf(chunkGenerator(key).getBiomeSource().possibleBiomes()),
                                biome ->
                                        chunkGenerator(key)
                                                .getBiomeGenerationSettings(biome)
                                                .features(),
                                true));
    }

    public FeatureCoordinates featureDecorationCoordinates(
            String featureId, String dimensionId, dev.lootradar.core.structure.StructureSpec spec) {
        if (spec != null && spec.featureIndex() != null && spec.featureStep() != null) {
            return new FeatureCoordinates(spec.featureStep(), spec.featureIndex());
        }
        return featureDecorationCoordinates(featureId, dimensionId);
    }

    public FeatureCoordinates featureDecorationCoordinates(String featureId, String dimensionId) {
        return featureCoordinateCache.computeIfAbsent(
                featureId + "@" + dimensionId,
                ignored -> computeFeatureDecorationCoordinates(featureId, dimensionId));
    }

    private FeatureCoordinates computeFeatureDecorationCoordinates(
            String featureId, String dimensionId) {
        var placedFeatureRegistry = registries.lookupOrThrow(Registries.PLACED_FEATURE);
        var key = ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(featureId));
        net.minecraft.world.level.levelgen.placement.PlacedFeature target =
                placedFeatureRegistry.getValueOrThrow(key);
        var featuresPerStep = featuresPerStep(dimensionId);
        for (int step = 0; step < featuresPerStep.size(); step++) {
            var stepData = featuresPerStep.get(step);
            int index = stepData.indexMapping().applyAsInt(target);
            if (index >= 0
                    && index < stepData.features().size()
                    && stepData.features().get(index) == target) {
                return new FeatureCoordinates(step, index);
            }
        }
        throw new IllegalStateException("feature not present in any decoration step: " + featureId);
    }

    public net.minecraft.world.level.levelgen.placement.PlacedFeature placedFeature(
            String featureId) {
        return registries
                .lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(
                        ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(featureId)));
    }

    public List<ChunkPos> ringPositions(String setId, String dimensionId) {
        var setRegistry = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        String wanted = setId.contains(":") ? setId : "minecraft:" + setId;
        var holder =
                setRegistry
                        .listElements()
                        .filter(candidate -> candidate.key().identifier().toString().equals(wanted))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "no structure set " + wanted + " in registry"));
        if (!(holder.value().placement()
                instanceof
                net.minecraft.world.level.levelgen.structure.placement
                                .ConcentricRingsStructurePlacement
                        rings)) {
            throw new IllegalArgumentException(setId + " is not concentric-rings");
        }
        List<ChunkPos> positions = context(dimensionId).structureState().getRingPositionsFor(rings);
        return positions == null ? List.of() : positions;
    }

    @Override
    public void close() {
        try {
            resourceManager.close();
        } catch (Exception ignored) {
        }
        try {
            storageAccess.close();
        } catch (Exception ignored) {
        }
        try {
            if (scratchPath != null) {
                deleteRecursively(scratchPath);
            }
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
