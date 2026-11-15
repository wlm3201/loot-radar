package dev.lootradar.engine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * 用动态代理实现的轻量 {@code WorldGenLevel}：只记录结构放置过程中写入的方块与方块实体， 不生成完整存档。{@link #lootSources()}
 * 从记录的方块实体里提取战利品表与 lootTableSeed， 这就是「不跑真实服务器也能预测箱子内容」的关键。
 *
 * <p>写入被限制在装饰中心区块的 3×3 邻域内（{@code canWrite}）；高度查询在 原生地形之上叠加本次放置产生的方块，保证沉船、埋藏宝藏等高度敏感结构可复现。
 */
final class RecordingLevel implements InvocationHandler {

    private final VanillaRuntime runtime;
    private final String dimensionId;
    private final long worldSeed;
    private final RandomSource levelRandom;
    private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();
    private final Map<Long, Integer> heightCache = new LinkedHashMap<>();
    private final Map<Long, ChunkAccess> chunks = new LinkedHashMap<>();
    private final Map<Long, Holder<Biome>> noiseBiomes = new java.util.HashMap<>();
    private final List<ContainerRecord> entityContainers = new ArrayList<>();
    private final boolean noiseTerrain;
    private TerrainView terrain;
    private final List<net.minecraft.world.level.levelgen.structure.StructureStart>
            terrainStructures;
    private final WorldGenLevel proxy;
    private final BiomeManager biomeManager;
    private final net.minecraft.server.level.ServerLevel suppressingLevel;
    private long subTick;
    private net.minecraft.world.level.ChunkPos decorationCenter;
    private final Map<Long, Integer> overlayTop = new java.util.HashMap<>();
    private final Map<Heightmap.Types, Map<Long, Integer>> overlayHeights =
            new java.util.EnumMap<>(Heightmap.Types.class);

    void setDecorationCenter(net.minecraft.world.level.ChunkPos center) {
        decorationCenter = center;
    }

    ChunkAccess terrainChunk(net.minecraft.world.level.ChunkPos pos) {
        return terrain.chunk(pos);
    }

    java.util.Set<Holder<Biome>> biomesInChunk(net.minecraft.world.level.ChunkPos pos) {
        return runtime.biomePalette(
                dimensionId,
                pos,
                () -> {
                    ChunkAccess biomeChunk = newChunk(pos);
                    var manager =
                            new net.minecraft.world.level.StructureManager(
                                    proxy,
                                    new net.minecraft.world.level.levelgen.WorldOptions(
                                            worldSeed, true, false),
                                    null);
                    MinecraftVersionAdapter.fillBiomes(
                            runtime.chunkGenerator(dimensionId),
                            runtime.randomState(dimensionId),
                            net.minecraft.world.level.levelgen.blending.Blender.empty(),
                            manager,
                            biomeChunk);
                    java.util.Set<Holder<Biome>> result = new java.util.HashSet<>();
                    for (LevelChunkSection section : biomeChunk.getSections())
                        section.getBiomes().getAll(result::add);
                    return result;
                });
    }

    // 原版世界生成区一次只覆盖中心区块的 3×3 邻域，超出范围的写入应被丢弃。
    private boolean canWrite(BlockPos pos) {
        return decorationCenter == null
                || (Math.abs((pos.getX() >> 4) - decorationCenter.x()) <= 1
                        && Math.abs((pos.getZ() >> 4) - decorationCenter.z()) <= 1);
    }

    private int height(Heightmap.Types type, int x, int z) {
        if (!noiseTerrain) return terrainHeight(x, z);
        int base = terrain.height(type, x, z);
        if (type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.WORLD_SURFACE_WG)
            return base;
        long key = net.minecraft.world.level.ChunkPos.pack(x, z);
        return overlayHeights
                .computeIfAbsent(type, ignored -> new java.util.HashMap<>())
                .computeIfAbsent(
                        key,
                        ignored -> {
                            int top =
                                    Math.max(
                                            base - 1,
                                            overlayTop.getOrDefault(key, Integer.MIN_VALUE));
                            for (int y = top;
                                    y >= runtime.heightAccessor(dimensionId).getMinY();
                                    y--) {
                                if (type.isOpaque().test(blockState(new BlockPos(x, y, z))))
                                    return y + 1;
                            }
                            return runtime.heightAccessor(dimensionId).getMinY();
                        });
    }

    RecordingLevel(VanillaRuntime runtime, String dimensionId) {
        this(runtime, dimensionId, false);
    }

    RecordingLevel(VanillaRuntime runtime, String dimensionId, boolean noiseTerrain) {
        this(runtime, dimensionId, noiseTerrain, List.of(), runtime.terrainCache(dimensionId));
    }

    RecordingLevel(
            VanillaRuntime runtime,
            String dimensionId,
            List<net.minecraft.world.level.levelgen.structure.StructureStart> terrainStructures,
            NativeTerrainCache terrainCache) {
        this(runtime, dimensionId, true, terrainStructures, terrainCache);
    }

    private RecordingLevel(
            VanillaRuntime runtime,
            String dimensionId,
            boolean noiseTerrain,
            List<net.minecraft.world.level.levelgen.structure.StructureStart> terrainStructures,
            NativeTerrainCache terrainCache) {
        this.runtime = runtime;
        this.dimensionId =
                dimensionId.startsWith("minecraft:") ? dimensionId : "minecraft:" + dimensionId;
        this.worldSeed = runtime.worldSeed();
        this.noiseTerrain = noiseTerrain;
        this.terrainStructures = List.copyOf(terrainStructures);
        this.levelRandom = RandomSource.create(worldSeed);
        this.suppressingLevel =
                noiseTerrain
                        ? runtime.contextualLevel(dimensionId)
                        : runtime.suppressingServerLevel();
        this.proxy =
                (WorldGenLevel)
                        Proxy.newProxyInstance(
                                WorldGenLevel.class.getClassLoader(),
                                new Class<?>[] {WorldGenLevel.class},
                                this);
        this.biomeManager =
                new BiomeManager(
                        (quartX, quartY, quartZ) -> noiseBiome(quartX, quartY, quartZ),
                        BiomeManager.obfuscateSeed(worldSeed));
        this.terrain =
                noiseTerrain
                        ? MinecraftVersionAdapter.terrain(
                                runtime, dimensionId, proxy, this::newChunk, terrainCache)
                        : null;
    }

    WorldGenLevel level() {
        return proxy;
    }

    // 把放置结果翻译成 ContainerRecord：陶罐纹样、可疑沙子、宝库、普通容器、
    // 以及箱子矿车分别处理；宝库没有 lootTableSeed，交给序列预测器。
    List<ContainerRecord> lootSources() {
        List<ContainerRecord> result = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            BlockEntity be = entry.getValue();
            BlockPos pos = entry.getKey();
            String blockId =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(be.getBlockState().getBlock())
                            .toString();
            if (be instanceof DecoratedPotBlockEntity pot) {
                List<String> sherds = potSherdItems(pot);
                String table =
                        pot.getLootTable() == null
                                ? ""
                                : pot.getLootTable().identifier().toString();
                if (!sherds.isEmpty() && table.isEmpty()) {
                    result.add(
                            new ContainerRecord(
                                    pos, "", 0L, ContainerKind.POT_SHERD, blockId, sherds));
                }
                if (!table.isEmpty()) {
                    result.add(
                            new ContainerRecord(
                                    pos,
                                    table,
                                    pot.getLootTableSeed(),
                                    ContainerKind.CONTAINER,
                                    blockId,
                                    sherds));
                }
            } else if (be instanceof BrushableBlockEntity brushable) {
                var tag = brushable.saveWithFullMetadata(runtime.registries());
                String table =
                        tag.getStringOr(
                                net.minecraft.world.RandomizableContainer.LOOT_TABLE_TAG, "");
                long seed =
                        tag.getLongOr(
                                net.minecraft.world.RandomizableContainer.LOOT_TABLE_SEED_TAG, 0L);
                if (!table.isEmpty()) {
                    result.add(
                            new ContainerRecord(
                                    pos,
                                    table,
                                    seed,
                                    ContainerKind.ARCHAEOLOGY,
                                    blockId,
                                    List.of()));
                }
            } else if (be
                    instanceof
                    net.minecraft.world.level.block.entity.vault.VaultBlockEntity vault) {
                result.add(
                        new ContainerRecord(
                                pos,
                                vault.getConfig().lootTable().identifier().toString(),
                                0L,
                                ContainerKind.VAULT,
                                blockId,
                                List.of()));
            } else if (be instanceof RandomizableContainer container) {
                ResourceKey<net.minecraft.world.level.storage.loot.LootTable> table =
                        container.getLootTable();
                if (table != null && !baseTerrainIsLava(pos)) {
                    result.add(
                            new ContainerRecord(
                                    pos,
                                    table.identifier().toString(),
                                    container.getLootTableSeed(),
                                    ContainerKind.CONTAINER,
                                    blockId,
                                    List.of()));
                }
            }
        }
        result.addAll(entityContainers);
        return List.copyOf(result);
    }

    private static List<String> potSherdItems(DecoratedPotBlockEntity pot) {
        PotDecorations decorations = pot.getDecorations();
        List<String> items = new ArrayList<>(4);
        for (net.minecraft.world.item.Item item : MinecraftVersionAdapter.potItems(decorations)) {
            items.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return items;
    }

    @Override
    public Object invoke(Object theProxy, Method method, Object[] nullableArgs) throws Throwable {
        Object[] args = nullableArgs == null ? new Object[0] : nullableArgs;
        String name = method.getName();

        if (method.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "toString" -> "RecordingLevel[" + dimensionId + "]";
                case "hashCode" -> System.identityHashCode(theProxy);
                case "equals" -> theProxy == args[0];
                default -> throw unsupported(method);
            };
        }

        switch (name) {
            case "registryAccess":
                return runtime.registries();
            case "holderLookup":
                {
                    @SuppressWarnings("unchecked")
                    ResourceKey<? extends Registry<Object>> key =
                            (ResourceKey<? extends Registry<Object>>) args[0];
                    return runtime.registries().lookupOrThrow(key);
                }
            case "getSeed":
                return worldSeed;
            case "getRandom":
                return levelRandom;
            case "getMinY":
                return runtime.heightAccessor(dimensionId).getMinY();
            case "getMaxY":
                return runtime.heightAccessor(dimensionId).getMaxY();
            case "getHeight":
                {
                    if (args.length == 0) {
                        return runtime.heightAccessor(dimensionId).getHeight();
                    }
                    if (args.length == 2) {
                        BlockPos pos = (BlockPos) args[1];
                        return height((Heightmap.Types) args[0], pos.getX(), pos.getZ());
                    }
                    return height((Heightmap.Types) args[0], (int) args[1], (int) args[2]);
                }
            case "getBaseHeight":
                return terrainHeight((int) args[0], (int) args[1]);
            case "getHeightmapPos":
                {
                    BlockPos pos = (BlockPos) args[1];
                    return new BlockPos(
                            pos.getX(),
                            height((Heightmap.Types) args[0], pos.getX(), pos.getZ()),
                            pos.getZ());
                }
            case "getSeaLevel":
                return runtime.chunkGenerator(dimensionId).getSeaLevel();
            case "dimensionType":
                return runtime.dimensionType(dimensionId);
            case "getDimensionKey":
                return runtime.levelKey(dimensionId);
            case "getBiomeManager":
                return biomeManager;
            case "getBiome":
                {
                    BlockPos pos = (BlockPos) args[0];
                    return biomeManager.getBiome(pos);
                }
            case "getUncachedNoiseBiome":
                return noiseBiome((int) args[0], (int) args[1], (int) args[2]);
            case "enabledFeatures":
                return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
            case "isClientSide":
                return false;
            case "ensureCanWrite":
                return canWrite((BlockPos) args[0]);
            case "hasChunk", "hasChunkAt", "isLoaded":
                return true;
            case "getChunk":
                return chunk(args);
            case "getChunkForCollisions":
                return chunk(args);
            case "getBlockState":
                return blockState((BlockPos) args[0]);
            case "isEmptyBlock":
                return blockState((BlockPos) args[0]).isAir();
            case "getFluidState":
                return blockState((BlockPos) args[0]).getFluidState();
            case "getBlockEntity":
                if (blockEntityTracker != null) {
                    blockEntityTracker.accept(((BlockPos) args[0]).immutable());
                }
                return blockEntity(method, args);
            case "setBlock":
                return setBlock((BlockPos) args[0], (BlockState) args[1]);
            case "removeBlock", "destroyBlock":
                {
                    BlockPos pos = ((BlockPos) args[0]).immutable();
                    return setBlock(pos, Blocks.AIR.defaultBlockState());
                }
            case "isStateAtPosition":
                {
                    @SuppressWarnings("unchecked")
                    Predicate<BlockState> predicate = (Predicate<BlockState>) args[1];
                    return predicate.test(blockState((BlockPos) args[0]));
                }
            case "isFluidAtPosition":
                {
                    @SuppressWarnings("unchecked")
                    Predicate<FluidState> predicate = (Predicate<FluidState>) args[1];
                    return predicate.test(blockState((BlockPos) args[0]).getFluidState());
                }
            case "getEntities", "getEntityCollisions", "players", "getEntitiesOfClass":
                return List.of();
            case "addFreshEntity", "addFreshEntityWithPassengers", "addWithUUID":
                if (args[0]
                                instanceof
                                net.minecraft.world.entity.vehicle.minecart.MinecartChest cart
                        && cart.getContainerLootTable() != null) {
                    entityContainers.add(
                            new ContainerRecord(
                                    cart.blockPosition().immutable(),
                                    cart.getContainerLootTable().identifier().toString(),
                                    cart.getContainerLootTableSeed(),
                                    ContainerKind.CONTAINER,
                                    "minecraft:chest_minecart",
                                    List.of()));
                }
                return true;
            case "nextSubTickCount":
                return subTick++;
            case "getLevel":
                return suppressingLevel;
            case "getLevelData":
                return SuppressingServerLevel.stubLevelData();
            case "getCurrentDifficultyAt":
                return new net.minecraft.world.DifficultyInstance(
                        net.minecraft.world.Difficulty.NORMAL, 0L, 0L, 0f);
            case "getDifficulty":
                return net.minecraft.world.Difficulty.NORMAL;
            case "structureManager":
                return new net.minecraft.world.level.StructureManager(
                        proxy,
                        new net.minecraft.world.level.levelgen.WorldOptions(worldSeed, true, false),
                        null);
            case "getServer", "getChunkSource", "getWorldBorder", "getLightEngine":
                return null;
            case "environmentAttributes":
                return net.minecraft.world.attribute.EnvironmentAttributeReader.EMPTY;
            case "getSkyDarken":
                return 0;
            case "getBrightness",
            "getRawBrightness",
            "getMaxLocalRawBrightness",
            "getEffectiveSkyBrightness":
                return 15;
            case "playSound",
            "addParticle",
            "levelEvent",
            "gameEvent",
            "sendBlockUpdated",
            "updateNeighborsAt",
            "neighborShapeChanged",
            "scheduleTick",
            "setCurrentlyGenerating",
            "blockUpdated":
                return null;
            default:
                {
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(theProxy, method, args);
                    }
                    throw unsupported(method);
                }
        }
    }

    private ChunkAccess chunk(Object[] args) {
        int chunkX;
        int chunkZ;
        if (args[0] instanceof BlockPos pos) {
            chunkX = Math.floorDiv(pos.getX(), 16);
            chunkZ = Math.floorDiv(pos.getZ(), 16);
        } else {
            chunkX = (int) args[0];
            chunkZ = (int) args[1];
        }
        long key = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
        return chunks.computeIfAbsent(
                key,
                ignored -> {
                    var pos = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
                    ChunkAccess chunk = newChunk(pos);
                    for (var start : terrainStructures) {
                        if (start.getChunkPos().equals(pos))
                            chunk.setStartForStructure(start.getStructure(), start);
                        if (Math.abs(start.getChunkPos().x() - chunkX) <= 8
                                && Math.abs(start.getChunkPos().z() - chunkZ) <= 8
                                && start.getBoundingBox()
                                        .intersects(
                                                pos.getMinBlockX(),
                                                pos.getMinBlockZ(),
                                                pos.getMaxBlockX(),
                                                pos.getMaxBlockZ())) {
                            chunk.addReferenceForStructure(
                                    start.getStructure(),
                                    net.minecraft.world.level.ChunkPos.pack(
                                            start.getChunkPos().x(), start.getChunkPos().z()));
                        }
                    }
                    return chunk;
                });
    }

    private ChunkAccess newChunk(net.minecraft.world.level.ChunkPos pos) {
        return new ProtoChunk(
                pos,
                UpgradeData.EMPTY,
                runtime.heightAccessor(dimensionId),
                PalettedContainerFactory.create(runtime.registries()),
                null);
    }

    private Object blockEntity(Method method, Object[] args) {
        BlockEntity be = blockEntities.get((BlockPos) args[0]);
        if (method.getReturnType() == Optional.class) {
            return be == null ? Optional.empty() : Optional.of(be);
        }
        return be;
    }

    // 方块写入同时刷新高度覆盖层：后续 getHeight 必须看见本次放置产生的新方块。
    private boolean setBlock(BlockPos mutablePos, BlockState state) {
        if (!canWrite(mutablePos)) return false;
        BlockPos pos = mutablePos.immutable();
        states.put(pos, state);
        long column = net.minecraft.world.level.ChunkPos.pack(pos.getX(), pos.getZ());
        overlayTop.merge(column, pos.getY(), Math::max);
        for (var heights : overlayHeights.values()) heights.remove(column);
        if (state.getBlock() instanceof EntityBlock entityBlock) {
            BlockEntity current = blockEntities.get(pos);
            if (current == null || !current.getBlockState().is(state.getBlock())) {
                BlockEntity created = entityBlock.newBlockEntity(pos, state);
                if (created != null) {
                    blockEntities.put(pos, created);
                }
            }
        } else {
            blockEntities.remove(pos);
        }
        return true;
    }

    private BlockState blockState(BlockPos pos) {
        BlockState result = resolveBlockState(pos);
        if (readTracker != null) {
            readTracker.accept(pos.immutable(), result);
        }
        return result;
    }

    private BlockState resolveBlockState(BlockPos pos) {
        BlockState placed = states.get(pos);
        if (placed != null) {
            return placed;
        }
        if (noiseTerrain) {
            return terrain.blockState(pos);
        }
        return switch (dimensionId) {
            case "minecraft:the_nether" -> Blocks.NETHERRACK.defaultBlockState();
            case "minecraft:the_end" -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    void trackReads(java.util.function.BiConsumer<BlockPos, BlockState> tracker) {
        this.readTracker = tracker;
    }

    void trackBlockEntityReads(java.util.function.Consumer<BlockPos> tracker) {
        this.blockEntityTracker = tracker;
    }

    void seedStates(Map<BlockPos, BlockState> seed) {
        states.putAll(seed);
    }

    BlockState stateOf(BlockPos pos) {
        return resolveBlockState(pos);
    }

    private java.util.function.BiConsumer<BlockPos, BlockState> readTracker;
    private java.util.function.Consumer<BlockPos> blockEntityTracker;

    private int terrainHeight(int blockX, int blockZ) {
        long key = net.minecraft.world.level.ChunkPos.pack(blockX, blockZ);
        return heightCache.computeIfAbsent(
                key, ignored -> runtime.surfaceHeight(dimensionId, blockX, blockZ));
    }

    private boolean baseTerrainIsLava(BlockPos pos) {
        if (terrain == null) {
            terrain =
                    MinecraftVersionAdapter.terrain(
                            runtime,
                            dimensionId,
                            proxy,
                            this::newChunk,
                            runtime.terrainCache(dimensionId));
        }
        return terrain.blockState(pos).getBlock() == Blocks.LAVA;
    }

    private Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        return noiseBiomes.computeIfAbsent(
                BlockPos.asLong(quartX, quartY, quartZ),
                ignored ->
                        MinecraftVersionAdapter.noiseBiome(
                                runtime, dimensionId, quartX, quartY, quartZ));
    }

    private static UnsupportedOperationException unsupported(Method method) {
        return new UnsupportedOperationException(
                "Unsupported structure world interaction: " + method.toGenericString());
    }

    @SuppressWarnings("unused")
    private static LevelChunkSection unusedSectionHint() {
        return null;
    }
}
