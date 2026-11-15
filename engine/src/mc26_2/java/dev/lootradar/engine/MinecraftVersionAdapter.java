package dev.lootradar.engine;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.*;

/**
 * 26.2 原生 API 适配层（默认编译版本）。与 26.1.2 相比 buildSurface 的参数改为 possibleBiomes；频率缩减枚举仍位于
 * StructurePlacement 中。
 */
public final class MinecraftVersionAdapter {

    static Holder<net.minecraft.world.level.biome.Biome> noiseBiome(
            VanillaRuntime runtime, String dimension, int x, int y, int z) {
        return runtime.chunkGenerator(dimension)
                .getBiomeSource()
                .getNoiseBiome(x, y, z, runtime.climateSampler(dimension));
    }

    static HolderLookup.Provider worldgen(WorldLoader.DataLoadContext context) {
        return context.datapackWorldgen();
    }

    static RandomState randomState(
            RegistryAccess.Frozen registries, Holder<NoiseGeneratorSettings> settings, long seed) {
        return RandomState.create(registries, settings.unwrapKey().orElseThrow(), seed);
    }

    static Climate.Sampler climate(RandomState state) {
        return state.sampler();
    }

    static StructureStart generate(
            Holder<Structure> holder, VanillaRuntime runtime, String dimension, ChunkPos pos) {
        return holder.value()
                .generate(
                        holder,
                        runtime.levelKey(dimension),
                        runtime.registries(),
                        runtime.chunkGenerator(dimension),
                        runtime.chunkGenerator(dimension).getBiomeSource(),
                        runtime.randomState(dimension),
                        runtime.templateManager(),
                        runtime.worldSeed(),
                        pos,
                        0,
                        runtime.heightAccessor(dimension),
                        holder.value().biomes()::contains);
    }

    static void placeFeature(
            PlacedFeature feature,
            WorldGenLevel level,
            ChunkGenerator generator,
            net.minecraft.util.RandomSource random,
            BlockPos pos) {
        feature.placeWithBiomeCheck(level, generator, random, pos);
    }

    static Iterable<Item> potItems(PotDecorations decorations) {
        return decorations.ordered();
    }

    static TerrainView terrain(
            VanillaRuntime runtime,
            String dimension,
            WorldGenLevel level,
            Function<ChunkPos, ChunkAccess> chunks) {
        return terrain(runtime, dimension, level, chunks, runtime.terrainCache(dimension));
    }

    static TerrainView terrain(
            VanillaRuntime runtime,
            String dimension,
            WorldGenLevel level,
            Function<ChunkPos, ChunkAccess> chunks,
            NativeTerrainCache cache) {
        return new TerrainView() {

            private final Map<Long, ChunkAccess> ready = new HashMap<>();

            public ChunkAccess chunk(ChunkPos pos) {
                return chunk(pos.x(), pos.z());
            }

            private ChunkAccess chunk(int x, int z) {
                long key = ChunkPos.pack(x, z);
                return ready.computeIfAbsent(
                        key,
                        ignored ->
                                cache.get(
                                        new ChunkPos(x, z),
                                        () -> {
                                            ChunkAccess chunk = chunks.apply(new ChunkPos(x, z));
                                            var manager =
                                                    new StructureManager(
                                                            level,
                                                            new WorldOptions(
                                                                    runtime.worldSeed(),
                                                                    true,
                                                                    false),
                                                            null);
                                            buildTerrain(runtime, dimension, level, chunk, manager);
                                            return chunk;
                                        }));
            }

            public BlockState blockState(BlockPos pos) {
                return chunk(pos.getX() >> 4, pos.getZ() >> 4).getBlockState(pos);
            }

            public int height(Heightmap.Types type, int x, int z) {
                return chunk(x >> 4, z >> 4).getHeight(type, x & 15, z & 15) + 1;
            }
        };
    }

    static void buildTerrain(
            VanillaRuntime runtime,
            String dimension,
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager manager) {
        var generator = (NoiseBasedChunkGenerator) runtime.chunkGenerator(dimension);
        var state = runtime.randomState(dimension);
        var blender = net.minecraft.world.level.levelgen.blending.Blender.empty();
        generator.createBiomes(state, blender, manager, chunk).join();
        ((ProtoChunk) chunk)
                .setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        generator.fillFromNoise(blender, state, manager, chunk).join();
        ((ProtoChunk) chunk)
                .setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.NOISE);
        generator.buildSurface(
                chunk,
                new WorldGenerationContext(generator, chunk.getHeightAccessorForGeneration()),
                state,
                manager,
                level.getBiomeManager(),
                blender,
                generator.getBiomeSource().possibleBiomes());
        ((ProtoChunk) chunk)
                .setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.SURFACE);
        carve(runtime, dimension, level, generator, (ProtoChunk) chunk);
        ((ProtoChunk) chunk)
                .setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.CARVERS);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
    }

    private static void carve(
            VanillaRuntime runtime,
            String dimension,
            WorldGenLevel level,
            NoiseBasedChunkGenerator generator,
            ProtoChunk chunk) {
        var state = runtime.randomState(dimension);
        var noise =
                chunk.getOrCreateNoiseChunk(
                        unused -> {
                            throw new IllegalStateException(
                                    "Terrain noise must be initialized before carving");
                        });
        var context =
                new net.minecraft.world.level.levelgen.carver.CarvingContext(
                        generator,
                        runtime.registries(),
                        chunk.getHeightAccessorForGeneration(),
                        noise,
                        state,
                        generator.generatorSettings().value().surfaceRule());
        var mask = chunk.getOrCreateCarvingMask();
        var random = new WorldgenRandom(new LegacyRandomSource(0L));
        var pos = chunk.getPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                var source = new ChunkPos(pos.x() + dx, pos.z() + dz);
                var biome = runtime.carverBiome(dimension, source);
                int index = 0;
                for (var holder : generator.getBiomeGenerationSettings(biome).getCarvers()) {
                    var carver = holder.value();
                    random.setLargeFeatureSeed(
                            runtime.worldSeed() + index++, source.x(), source.z());
                    if (carver.isStartChunk(random)) {
                        carver.carve(
                                context,
                                chunk,
                                level.getBiomeManager()::getBiome,
                                random,
                                noise.aquifer(),
                                source,
                                mask);
                    }
                }
            }
        }
    }

    public static net.minecraft.world.level.levelgen.structure.pieces
                    .StructurePieceSerializationContext
            serializationContext(
                    net.minecraft.server.packs.resources.ResourceManager resourceManager,
                    net.minecraft.core.RegistryAccess registries,
                    net.minecraft.world.level.levelgen.structure.templatesystem
                                    .StructureTemplateManager
                            templates) {
        return new net.minecraft.world.level.levelgen.structure.pieces
                .StructurePieceSerializationContext(resourceManager, registries, templates);
    }

    public static int firstFreeHeight(
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            int x,
            int z,
            net.minecraft.world.level.levelgen.Heightmap.Types type,
            net.minecraft.world.level.LevelHeightAccessor heightAccessor,
            net.minecraft.world.level.levelgen.RandomState randomState) {
        return generator.getBaseHeight(x, z, type, heightAccessor, randomState);
    }

    public static boolean isValidStart(
            net.minecraft.world.level.levelgen.structure.StructureStart start) {
        return start != null && start.isValid();
    }

    public static net.minecraft.world.level.levelgen.structure.StructureStart invalidStart() {
        return net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START;
    }

    public static net.minecraft.world.level.levelgen.structure.StructureStart loadStart(
            net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
                    context,
            net.minecraft.nbt.CompoundTag tag,
            long seed,
            net.minecraft.world.level.levelgen.structure.Structure structure) {
        return net.minecraft.world.level.levelgen.structure.StructureStart.loadStaticStart(
                context, tag, seed);
    }

    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
            loadTemplate(
                    net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block>
                            blockLookup,
                    net.minecraft.nbt.CompoundTag tag) {
        var copy =
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
        copy.load(blockLookup, tag);
        return copy;
    }

    static void fillBiomes(
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState state,
            net.minecraft.world.level.levelgen.blending.Blender blender,
            net.minecraft.world.level.StructureManager manager,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        generator.createBiomes(state, blender, manager, chunk).join();
    }

    public static boolean vanillaFrequencyAllows(
            String method, long seed, int salt, int x, int z, float frequency) {
        var vanillaMethod =
                net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
                        .FrequencyReductionMethod.valueOf(method.toUpperCase());
        return vanillaMethod.shouldGenerate(seed, salt, x, z, frequency);
    }
}
