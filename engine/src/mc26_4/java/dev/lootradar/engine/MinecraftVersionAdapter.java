package dev.lootradar.engine;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
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
 * 26.4（含 26.4-snapshot-7）原生 API 适配层：RandomState 用 createForTesting、 StructureStart.load 需要
 * structure 参数、StructureStart.isValid() 被移除， 因此 isValidStart 只判非空，invalidStart 返回 null。
 */
public final class MinecraftVersionAdapter {

    static Holder<net.minecraft.world.level.biome.Biome> noiseBiome(
            VanillaRuntime runtime, String dimension, int x, int y, int z) {
        return runtime.chunkGenerator(dimension)
                .getBiomeSource()
                .createResolver(runtime.climateSampler(dimension))
                .getNoiseBiome(x, y, z);
    }

    static HolderLookup.Provider worldgen(WorldLoader.DataLoadContext context) {
        return context.datapackWorldRegistries();
    }

    static RandomState randomState(
            RegistryAccess.Frozen registries, Holder<NoiseGeneratorSettings> settings, long seed) {
        return RandomState.createForTesting(
                registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
    }

    static Climate.Sampler climate(RandomState state) {
        return state.createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
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
                        runtime.climateSampler(dimension),
                        runtime.randomState(dimension),
                        runtime.templateManager(),
                        runtime.worldSeed(),
                        pos,
                        runtime.heightAccessor(dimension),
                        holder.value().biomes()::contains);
    }

    static void placeFeature(
            PlacedFeature feature,
            WorldGenLevel level,
            ChunkGenerator generator,
            net.minecraft.util.RandomSource random,
            BlockPos pos) {
        new net.minecraft.world.level.levelgen.placement.FeaturePlacer(level, generator)
                .placeWithBiomeCheck(feature, random, pos);
    }

    static Iterable<Item> potItems(PotDecorations decorations) {
        return List.of(
                        decorations.back(),
                        decorations.left(),
                        decorations.right(),
                        decorations.front())
                .stream()
                .map(item -> item.map(template -> template.item().value()).orElse(Items.BRICK))
                .toList();
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
                return ready.computeIfAbsent(
                        ChunkPos.pack(x, z),
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
        var generator = runtime.chunkGenerator(dimension);
        var state = runtime.randomState(dimension);
        var blender = net.minecraft.world.level.levelgen.blending.Blender.empty();
        generator
                .createBiomes(
                        generator.getBiomeSource().createUncachedResolver(state), state, chunk)
                .join();
        ((ProtoChunk) chunk)
                .setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES);
        generator
                .buildTerrain(
                        chunk,
                        blender,
                        state,
                        manager,
                        null,
                        generator.getBiomeSource().possibleBiomes())
                .join();
        ((ProtoChunk) chunk)
                .setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.TERRAIN);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
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
                .StructurePieceSerializationContext(registries, templates);
    }

    public static int firstFreeHeight(
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            int x,
            int z,
            net.minecraft.world.level.levelgen.Heightmap.Types type,
            net.minecraft.world.level.LevelHeightAccessor heightAccessor,
            net.minecraft.world.level.levelgen.RandomState randomState) {
        return generator.getFirstFreeHeight(x, z, type, heightAccessor, randomState);
    }

    public static boolean isValidStart(
            net.minecraft.world.level.levelgen.structure.StructureStart start) {
        return start != null;
    }

    public static net.minecraft.world.level.levelgen.structure.StructureStart invalidStart() {
        return null;
    }

    public static net.minecraft.world.level.levelgen.structure.StructureStart loadStart(
            net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
                    context,
            net.minecraft.nbt.CompoundTag tag,
            long seed,
            net.minecraft.world.level.levelgen.structure.Structure structure) {
        return net.minecraft.world.level.levelgen.structure.StructureStart.load(
                context, tag, seed, structure);
    }

    public static net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
            loadTemplate(
                    net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block>
                            blockLookup,
                    net.minecraft.nbt.CompoundTag tag) {
        return net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.load(
                blockLookup, tag);
    }

    static void fillBiomes(
            net.minecraft.world.level.chunk.ChunkGenerator generator,
            net.minecraft.world.level.levelgen.RandomState state,
            net.minecraft.world.level.levelgen.blending.Blender blender,
            net.minecraft.world.level.StructureManager manager,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        generator
                .createBiomes(
                        generator.getBiomeSource().createUncachedResolver(state), state, chunk)
                .join();
    }

    public static boolean vanillaFrequencyAllows(
            String method, long seed, int salt, int x, int z, float frequency) {
        var vanillaMethod =
                net.minecraft.world.level.levelgen.structure.placement
                        .AbstractSpreadingStructurePlacement.FrequencyReductionMethod.valueOf(
                        method.toUpperCase());
        return vanillaMethod.shouldGenerate(seed, salt, x, z, frequency);
    }
}
