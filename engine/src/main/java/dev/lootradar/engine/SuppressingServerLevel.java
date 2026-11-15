package dev.lootradar.engine;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.objenesis.ObjenesisStd;

/** 通过 Objenesis 绕过构造器的 {@code ServerLevel} 空壳：只提供战利品上下文所需的 注册表、种子、生物群系与维度信息，禁止任何真实存档/网络/实体查询。 */
final class SuppressingServerLevel extends SuppressingServerLevelBase {

    private static final ObjenesisStd OBJENESIS = new ObjenesisStd();
    private long recordingSeed;
    private MinecraftServer stubServer;
    private net.minecraft.core.RegistryAccess registryAccess;
    private java.util.concurrent.atomic.AtomicInteger nextEntityId;
    private VanillaRuntime runtime;
    private String dimensionId;
    private net.minecraft.world.level.biome.BiomeManager biomeManager;

    private SuppressingServerLevel() {
        super();
    }

    static SuppressingServerLevel create(
            long worldSeed,
            MinecraftServer stubServer,
            net.minecraft.core.RegistryAccess registryAccess) {
        SuppressingServerLevel level = OBJENESIS.newInstance(SuppressingServerLevel.class);
        level.recordingSeed = worldSeed;
        level.stubServer = stubServer;
        level.registryAccess = registryAccess;
        level.nextEntityId = new java.util.concurrent.atomic.AtomicInteger(1);
        return level;
    }

    static SuppressingServerLevel createContextual(
            VanillaRuntime runtime, String dimensionId, MinecraftServer server) {
        SuppressingServerLevel level = create(runtime.worldSeed(), server, runtime.registries());
        level.runtime = runtime;
        level.dimensionId = dimensionId;
        level.biomeManager =
                new net.minecraft.world.level.biome.BiomeManager(
                        (x, y, z) ->
                                MinecraftVersionAdapter.noiseBiome(runtime, dimensionId, x, y, z),
                        net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(
                                runtime.worldSeed()));
        return level;
    }

    @Override
    public boolean isLoaded(net.minecraft.core.BlockPos pos) {
        return runtime != null;
    }

    @Override
    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(
            net.minecraft.core.BlockPos pos) {
        return getBiomeManager().getBiome(pos);
    }

    public net.minecraft.world.level.biome.BiomeManager getBiomeManager() {
        if (biomeManager == null)
            throw new IllegalStateException("A dimension is required for biome queries");
        return biomeManager;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return runtime == null ? Level.OVERWORLD : runtime.levelKey(dimensionId);
    }

    public int getNextEntityId() {
        return nextEntityId.getAndIncrement();
    }

    @Override
    public net.minecraft.core.RegistryAccess registryAccess() {
        return registryAccess;
    }

    @Override
    public net.minecraft.core.BlockPos findNearestMapStructure(
            net.minecraft.tags.TagKey<net.minecraft.world.level.levelgen.structure.Structure>
                    structureTag,
            net.minecraft.core.BlockPos origin,
            int maxSearchRadius,
            boolean createReference) {
        return null;
    }

    public net.minecraft.core.BlockPos findNearestMapStructure(
            net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.structure.Structure>
                    structures,
            net.minecraft.core.BlockPos origin,
            int maxSearchRadius,
            boolean createReference) {
        return null;
    }

    private net.minecraft.world.level.StructureManager structureManager;

    @Override
    public net.minecraft.world.level.StructureManager structureManager() {
        if (structureManager == null) {
            structureManager = new NoStructureManager();
        }
        return structureManager;
    }

    private static final class NoStructureManager
            extends net.minecraft.world.level.StructureManager {
        NoStructureManager() {
            super(null, null, null);
        }

        public net.minecraft.world.level.levelgen.structure.StructureStart getStructureAt(
                net.minecraft.core.BlockPos pos,
                net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.structure.Structure>
                        structures) {
            return null;
        }

        public net.minecraft.world.level.levelgen.structure.StructureStart getStructureAt(
                net.minecraft.core.BlockPos pos,
                net.minecraft.world.level.levelgen.structure.Structure structure) {
            return null;
        }

        public net.minecraft.world.level.levelgen.structure.StructureStart getStructureWithPieceAt(
                net.minecraft.core.BlockPos pos,
                net.minecraft.tags.TagKey<net.minecraft.world.level.levelgen.structure.Structure>
                        tag) {
            return null;
        }

        public net.minecraft.world.level.levelgen.structure.StructureStart getStructureWithPieceAt(
                net.minecraft.core.BlockPos pos,
                net.minecraft.core.HolderSet<net.minecraft.world.level.levelgen.structure.Structure>
                        structures) {
            return null;
        }

        public net.minecraft.world.level.levelgen.structure.StructureStart getStructureWithPieceAt(
                net.minecraft.core.BlockPos pos,
                java.util.function.Predicate<
                                net.minecraft.core.Holder<
                                        net.minecraft.world.level.levelgen.structure.Structure>>
                        predicate) {
            return null;
        }

        public net.minecraft.world.level.levelgen.structure.StructureStart getStructureWithPieceAt(
                int x,
                int y,
                int z,
                net.minecraft.world.level.levelgen.structure.Structure structure) {
            return null;
        }
    }

    @Override
    public MinecraftServer getServer() {
        return stubServer;
    }

    @Override
    public ServerLevelData getLevelData() {
        return stubLevelData();
    }

    @Override
    public int getSeaLevel() {
        return runtime == null ? 63 : runtime.chunkGenerator(dimensionId).getSeaLevel();
    }

    static ServerLevelData stubLevelData() {
        if (levelDataStub == null) {
            levelDataStub =
                    (ServerLevelData)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    ServerLevelData.class.getClassLoader(),
                                    new Class<?>[] {ServerLevelData.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getDifficulty" ->
                                                        net.minecraft.world.Difficulty.NORMAL;
                                                case "getGameType" ->
                                                        net.minecraft.world.level.GameType.SURVIVAL;
                                                case "getSpawnPos" ->
                                                        net.minecraft.core.BlockPos.ZERO;
                                                case "getLevelName" -> "loot-radar";
                                                case "isHardcore",
                                                        "isRaining",
                                                        "isThundering",
                                                        "isInitialized",
                                                        "isFlatWorld",
                                                        "isDebugWorld" ->
                                                        false;
                                                case "getGameTime",
                                                        "getDayTime",
                                                        "getClearWeatherTime",
                                                        "getRainTime",
                                                        "getThunderTime" ->
                                                        0L;
                                                default -> inertDefault(method.getReturnType());
                                            });
        }
        return levelDataStub;
    }

    private static Object inertDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    private static ServerLevelData levelDataStub;

    @Override
    public void blockEntityChanged(net.minecraft.core.BlockPos pos) {}

    @Override
    public FeatureFlagSet enabledFeatures() {
        return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public long getSeed() {
        return recordingSeed;
    }

    @Override
    public void playSound(
            Entity source,
            double x,
            double y,
            double z,
            SoundEvent sound,
            SoundSource category,
            float volume,
            float pitch) {}
}
