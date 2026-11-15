package dev.lootradar.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;

/**
 * 补放「引用到当前区块」的矿井起点，并维护走廊蜘蛛网（NBT 里的 {@code sc}）状态。
 *
 * <p>矿井走廊是否生成蜘蛛网取决于放置顺序中的共享状态，因此 {@code requiredStateChunks} 会从后往前算出哪些区块必须实际重放，不能只扫描产出区块。
 */
final class PreStructureMineshafts {
    private record Key(String structure, ChunkPos root) {}

    private final VanillaRuntime runtime;
    private final String dimension;
    private final List<String> structures;
    private final Map<Key, StructureStart> starts = new HashMap<>();
    private final Map<Key, List<BoundingBox>> spiderBoxes = new HashMap<>();

    PreStructureMineshafts(VanillaRuntime runtime, String dimension) {
        this.runtime = runtime;
        this.dimension = dimension;
        var registry = runtime.registries().lookupOrThrow(Registries.STRUCTURE);
        structures =
                registry.stream()
                        .map(s -> registry.getKey(s).toString())
                        .filter(MineshaftBiomes::isMineshaft)
                        .toList();
    }

    boolean hasSpiderState(ChunkPos chunk) {
        for (String structure : structures) {
            for (ChunkPos root :
                    runtime.mineshaftReferences(dimension).startsForChunk(structure, chunk)) {
                Key key = new Key(structure, root);
                var boxes =
                        spiderBoxes.computeIfAbsent(
                                key,
                                ignored ->
                                        start(key).getPieces().stream()
                                                .filter(
                                                        piece ->
                                                                piece
                                                                                instanceof
                                                                                MineshaftPieces
                                                                                        .MineShaftCorridor
                                                                        && piece.createTag(
                                                                                        runtime
                                                                                                .structurePieceContext())
                                                                                .getBooleanOr(
                                                                                        "sc",
                                                                                        false))
                                                .map(piece -> piece.getBoundingBox())
                                                .toList());
                if (boxes.stream()
                        .anyMatch(
                                box ->
                                        box.intersects(
                                                chunk.getMinBlockX(),
                                                chunk.getMinBlockZ(),
                                                chunk.getMaxBlockX(),
                                                chunk.getMaxBlockZ()))) return true;
            }
        }
        return false;
    }

    Set<ChunkPos> requiredStateChunks(List<ChunkPos> orderedChunks, Set<ChunkPos> outputs) {
        Set<ChunkPos> required = new HashSet<>(outputs);
        Set<Key> laterRoots = new HashSet<>();
        for (int i = orderedChunks.size() - 1; i >= 0; i--) {
            ChunkPos chunk = orderedChunks.get(i);
            List<Key> references =
                    structures.stream()
                            .flatMap(
                                    structure ->
                                            runtime
                                                    .mineshaftReferences(dimension)
                                                    .startsForChunk(structure, chunk)
                                                    .stream()
                                                    .map(root -> new Key(structure, root)))
                            .toList();
            boolean changesLaterState =
                    references.stream()
                            .anyMatch(
                                    key ->
                                            laterRoots.contains(key)
                                                    && spiderBoxes
                                                            .computeIfAbsent(
                                                                    key,
                                                                    ignored ->
                                                                            start(key)
                                                                                    .getPieces()
                                                                                    .stream()
                                                                                    .filter(
                                                                                            piece ->
                                                                                                    piece
                                                                                                                    instanceof
                                                                                                                    MineshaftPieces
                                                                                                                            .MineShaftCorridor
                                                                                                            && piece.createTag(
                                                                                                                            runtime
                                                                                                                                    .structurePieceContext())
                                                                                                                    .getBooleanOr(
                                                                                                                            "sc",
                                                                                                                            false))
                                                                                    .map(
                                                                                            piece ->
                                                                                                    piece
                                                                                                            .getBoundingBox())
                                                                                    .toList())
                                                            .stream()
                                                            .anyMatch(
                                                                    box ->
                                                                            box.intersects(
                                                                                    chunk
                                                                                            .getMinBlockX(),
                                                                                    chunk
                                                                                            .getMinBlockZ(),
                                                                                    chunk
                                                                                            .getMaxBlockX(),
                                                                                    chunk
                                                                                            .getMaxBlockZ())));
            if (required.contains(chunk) || changesLaterState) {
                required.add(chunk);
                laterRoots.addAll(references);
            }
        }
        return Set.copyOf(required);
    }

    void place(RecordingLevel recording, ChunkPos chunk) {
        var level = recording.level();
        var manager =
                new StructureManager(
                        level, new WorldOptions(runtime.worldSeed(), true, false), null);
        var bounds =
                new BoundingBox(
                        chunk.getMinBlockX(),
                        level.getMinY(),
                        chunk.getMinBlockZ(),
                        chunk.getMaxBlockX(),
                        level.getMaxY(),
                        chunk.getMaxBlockZ());
        var stream =
                DecorationStreams.open(
                        runtime.worldSeed(), chunk.getMinBlockX(), chunk.getMinBlockZ());
        var random = stream.random();
        long decorationSeed = stream.decorationSeed();
        for (String structure : structures) {
            var coordinates = runtime.structureDecorationCoordinates(structure);
            random.setFeatureSeed(
                    decorationSeed, coordinates.indexWithinStep(), coordinates.step());
            for (ChunkPos root :
                    runtime.mineshaftReferences(dimension).startsForChunk(structure, chunk)) {
                start(new Key(structure, root))
                        .placeInChunk(
                                level,
                                manager,
                                runtime.chunkGenerator(dimension),
                                random,
                                bounds,
                                chunk);
            }
        }
    }

    private StructureStart start(Key key) {
        return starts.computeIfAbsent(
                key, ignored -> runtime.generateStructure(key.structure(), dimension, key.root()));
    }
}
