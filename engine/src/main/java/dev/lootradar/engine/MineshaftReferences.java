package dev.lootradar.engine;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/** 反查「哪些矿井起点会覆盖指定区块」。原版结构引用半径为 8 个区块， 因此这里在 ±8 范围内枚举 placement 命中的起点，生成其包围盒并按结构 id 过滤。 */
public final class MineshaftReferences {
    private static final int REFERENCE_RADIUS = 8;

    private final VanillaRuntime runtime;
    private final String dimension;
    private final StructurePlacement placement;
    private final ChunkGeneratorStructureState structureState;
    private final List<VanillaRuntime.StructureSetMember> members;
    private final Map<Long, Optional<StartMetadata>> starts = new ConcurrentHashMap<>();
    private final Map<ReferenceKey, List<ChunkPos>> references = new ConcurrentHashMap<>();

    public MineshaftReferences(VanillaRuntime runtime, String dimension) {
        this.runtime = runtime;
        this.dimension = dimension;
        var structureSets = runtime.registries().lookupOrThrow(Registries.STRUCTURE_SET);
        var set =
                structureSets.getValueOrThrow(
                        ResourceKey.create(
                                Registries.STRUCTURE_SET,
                                Identifier.parse("minecraft:mineshafts")));
        this.placement = set.placement();
        this.structureState =
                runtime.chunkGenerator(dimension)
                        .createState(
                                structureSets, runtime.randomState(dimension), runtime.worldSeed());
        var structures = runtime.registries().lookupOrThrow(Registries.STRUCTURE);
        this.members =
                set.structures().stream()
                        .map(
                                entry ->
                                        new VanillaRuntime.StructureSetMember(
                                                structures
                                                        .getKey(entry.structure().value())
                                                        .toString(),
                                                entry.weight()))
                        .toList();
    }

    public List<ChunkPos> startsForChunk(String structureId, ChunkPos target) {
        if (!MineshaftBiomes.isMineshaft(structureId)) {
            throw new IllegalArgumentException("Not a mineshaft structure: " + structureId);
        }
        return references.computeIfAbsent(
                new ReferenceKey(structureId, target), this::findReferences);
    }

    private List<ChunkPos> findReferences(ReferenceKey key) {
        ChunkPos target = key.target();
        var vanillaReferences = new LongOpenHashSet();
        for (int x = target.x() - REFERENCE_RADIUS; x <= target.x() + REFERENCE_RADIUS; x++) {
            for (int z = target.z() - REFERENCE_RADIUS; z <= target.z() + REFERENCE_RADIUS; z++) {
                if (!placement.isStructureChunk(structureState, x, z)) continue;
                long packed = ChunkPos.pack(x, z);
                Optional<StartMetadata> metadata =
                        starts.computeIfAbsent(packed, this::generateMetadata);
                if (metadata.isPresent() && metadata.get().intersects(key.structureId(), target)) {
                    vanillaReferences.add(packed);
                }
            }
        }
        List<ChunkPos> ordered = new ArrayList<>(vanillaReferences.size());
        for (long packed : vanillaReferences) ordered.add(ChunkPos.unpack(packed));
        return List.copyOf(ordered);
    }

    private Optional<StartMetadata> generateMetadata(long packed) {
        ChunkPos chunk = ChunkPos.unpack(packed);
        var start = runtime.generateSelectedStructure(members, dimension, chunk);
        if (!MinecraftVersionAdapter.isValidStart(start)) return Optional.empty();
        String id =
                runtime.registries()
                        .lookupOrThrow(Registries.STRUCTURE)
                        .getKey(start.getStructure())
                        .toString();
        var box = start.getBoundingBox();
        return Optional.of(new StartMetadata(id, box.minX(), box.minZ(), box.maxX(), box.maxZ()));
    }

    private record ReferenceKey(String structureId, ChunkPos target) {}

    private record StartMetadata(String structureId, int minX, int minZ, int maxX, int maxZ) {
        boolean intersects(String requestedId, ChunkPos target) {
            return structureId.equals(requestedId)
                    && maxX >= target.getMinBlockX()
                    && minX <= target.getMaxBlockX()
                    && maxZ >= target.getMinBlockZ()
                    && minZ <= target.getMaxBlockZ();
        }
    }
}
