package dev.lootradar.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * 沉船专用快速路径：不做逐区块放置，直接读取结构模板 NBT，把内嵌容器与结构方块 数据标记换算成世界坐标与 lootTableSeed。
 *
 * <p>标记映射与高度算法对齐原版 {@code ShipwreckPieces} （{@code MARKERS_TO_LOOT}、{@code anchorY} 的平均海床高度、搁浅船的
 * {@code minY - sizeY/2 - nextInt(3)}）；搁浅船在起始区块还会先消耗一次 nextInt(3)， 因此 {@code containerSeed}
 * 需要按区块累加被消耗的随机数序号。
 */
public final class TemplateFastPath {

    // 与 ShipwreckPieces.MARKERS_TO_LOOT 保持一致。
    private static final Map<String, String> MARKERS_TO_LOOT = new HashMap<>();

    static {
        MARKERS_TO_LOOT.put("supply_chest", "minecraft:chests/shipwreck_supply");
        MARKERS_TO_LOOT.put("map_chest", "minecraft:chests/shipwreck_map");
        MARKERS_TO_LOOT.put("treasure_chest", "minecraft:chests/shipwreck_treasure");
    }

    private TemplateFastPath() {}

    public static boolean supports(String structureId) {
        return "minecraft:shipwreck".equals(structureId)
                || "minecraft:shipwreck_beached".equals(structureId);
    }

    public static List<ContainerRecord> scan(
            VanillaRuntime runtime, String dimensionId, String structureId, StructureStart start) {
        if (!supports(structureId) || !MinecraftVersionAdapter.isValidStart(start)) {
            return null;
        }
        VanillaRuntime.DecorationCoordinates decoration =
                runtime.structureDecorationCoordinates(structureId);
        RecordingLevel heights = new RecordingLevel(runtime, dimensionId, true);
        {
            List<ContainerRecord> result = new ArrayList<>();
            Map<ChunkPos, Integer> consumedPerChunk = new HashMap<>();
            for (StructurePiece piece : start.getPieces()) {
                var tag = piece.createTag(runtime.structurePieceContext());
                boolean beached = "minecraft:shipwreck_beached".equals(structureId);
                String templateId = tag.getString("Template").orElse("");
                if (templateId.isEmpty()) return null;
                StructureTemplate template =
                        runtime.templateManager().get(Identifier.parse(templateId)).orElse(null);
                if (template == null) return null;
                if (!"NONE".equals(tag.getString("Mirror").orElse("NONE"))) return null;
                Rotation rotation = Rotation.valueOf(tag.getString("Rot").orElse("NONE"));
                BlockPos templatePos =
                        new BlockPos(
                                tag.getIntOr("TPX", 0),
                                tag.getIntOr("TPY", 0),
                                tag.getIntOr("TPZ", 0));
                var size = template.getSize();
                var bbox = piece.getBoundingBox();
                ChunkPos drawChunk = new ChunkPos(bbox.minX() >> 4, bbox.minZ() >> 4);
                int placeY = templatePos.getY();
                if (beached) {
                    placeY =
                            beachedY(
                                    heights.level(),
                                    runtime,
                                    decoration,
                                    drawChunk,
                                    templatePos,
                                    size);
                } else if (!tag.getBooleanOr("height_adjusted", false)) {
                    placeY = anchorY(heights.level(), template, templatePos);
                }
                int originX = bbox.minX() - templatePos.getX();
                int originZ = bbox.minZ() - templatePos.getZ();
                BlockPos placePos = new BlockPos(templatePos.getX(), placeY, templatePos.getZ());
                List<StructureBlockInfo> embedded = new ArrayList<>();
                for (net.minecraft.world.level.block.Block container :
                        new net.minecraft.world.level.block.Block[] {
                            Blocks.CHEST, Blocks.BARREL, Blocks.DECORATED_POT
                        }) {
                    for (StructureBlockInfo info :
                            template.filterBlocks(
                                    BlockPos.ZERO, new StructurePlaceSettings(), container)) {
                        if (info.nbt() != null) {
                            embedded.add(info);
                        }
                    }
                }
                List<StructureBlockInfo> markers =
                        template.filterBlocks(
                                BlockPos.ZERO,
                                new StructurePlaceSettings(),
                                Blocks.STRUCTURE_BLOCK);
                Map<ChunkPos, Integer> embeddedPerChunk = new HashMap<>();
                for (StructureBlockInfo info : embedded) {
                    String table = info.nbt().getString("LootTable").orElse("");
                    BlockPos containerPos =
                            toWorld(info.pos(), rotation, size, originX, originZ, placePos);
                    ChunkPos chunk = ChunkPos.containing(containerPos);
                    int index = embeddedPerChunk.merge(chunk, 1, Integer::sum) - 1;
                    consumedPerChunk.merge(chunk, 1, Integer::sum);
                    if (!table.isEmpty()) {
                        result.add(
                                record(
                                        containerPos,
                                        table,
                                        containerSeed(
                                                runtime,
                                                decoration,
                                                chunk,
                                                index,
                                                beached && chunk.equals(drawChunk)),
                                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                                .getKey(info.state().getBlock())
                                                .toString()));
                    }
                }
                for (StructureBlockInfo info : markers) {
                    String table = markerTable(info);
                    if (table == null) continue;
                    BlockPos containerPos =
                            toWorld(info.pos(), rotation, size, originX, originZ, placePos).below();
                    ChunkPos chunk = ChunkPos.containing(containerPos);
                    int index = consumedPerChunk.merge(chunk, 1, Integer::sum) - 1;
                    result.add(
                            record(
                                    containerPos,
                                    table,
                                    containerSeed(
                                            runtime,
                                            decoration,
                                            chunk,
                                            index,
                                            beached && chunk.equals(drawChunk)),
                                    "minecraft:chest"));
                }
            }
            return List.copyOf(result);
        }
    }

    private static ContainerRecord record(BlockPos pos, String table, long seed, String block) {
        return new ContainerRecord(pos, table, seed, ContainerKind.CONTAINER, block, List.of());
    }

    private static BlockPos toWorld(
            BlockPos template,
            Rotation rotation,
            net.minecraft.core.Vec3i size,
            int originX,
            int originZ,
            BlockPos placePos) {
        int x = template.getX();
        int z = template.getZ();
        int wx;
        int wz;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                wx = originX + (size.getZ() - 1 - z);
                wz = originZ + x;
            }
            case CLOCKWISE_180 -> {
                wx = originX + (size.getX() - 1 - x);
                wz = originZ + (size.getZ() - 1 - z);
            }
            case COUNTERCLOCKWISE_90 -> {
                wx = originX + z;
                wz = originZ + (size.getX() - 1 - x);
            }
            default -> {
                wx = originX + x;
                wz = originZ + z;
            }
        }
        return new BlockPos(
                placePos.getX() + wx, placePos.getY() + template.getY(), placePos.getZ() + wz);
    }

    private static String markerTable(StructureBlockInfo info) {
        if (info.nbt() == null || !info.state().is(Blocks.STRUCTURE_BLOCK)) return null;
        if (!"DATA".equals(info.nbt().getString("mode").orElse(""))) return null;
        return MARKERS_TO_LOOT.get(info.nbt().getString("metadata").orElse(""));
    }

    private static int anchorY(
            net.minecraft.world.level.WorldGenLevel level,
            StructureTemplate template,
            BlockPos templatePos) {
        var size = template.getSize();
        int area = size.getX() * size.getZ();
        if (area == 0) {
            return level.getHeight(
                    Heightmap.Types.OCEAN_FLOOR_WG, templatePos.getX(), templatePos.getZ());
        }
        long sum = 0;
        for (BlockPos pos :
                BlockPos.betweenClosed(
                        templatePos, templatePos.offset(size.getX() - 1, 0, size.getZ() - 1))) {
            sum += level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, pos.getX(), pos.getZ());
        }
        return (int) (sum / area);
    }

    private static long containerSeed(
            VanillaRuntime runtime,
            VanillaRuntime.DecorationCoordinates decoration,
            ChunkPos chunk,
            int index,
            boolean startDrawFirst) {
        WorldgenRandom random = placementStream(runtime, decoration, chunk);
        if (startDrawFirst) random.nextInt(BEACHED_HEIGHT_JITTER);
        long seed = 0;
        for (int i = 0; i <= index; i++) {
            seed = random.nextLong();
        }
        return seed;
    }

    private static WorldgenRandom placementStream(
            VanillaRuntime runtime,
            VanillaRuntime.DecorationCoordinates decoration,
            ChunkPos chunk) {
        return DecorationStreams.placement(
                runtime.worldSeed(),
                chunk.getMinBlockX(),
                chunk.getMinBlockZ(),
                decoration.indexWithinStep(),
                decoration.step());
    }

    private static final int BEACHED_HEIGHT_JITTER = 3;

    private static int beachedY(
            net.minecraft.world.level.WorldGenLevel level,
            VanillaRuntime runtime,
            VanillaRuntime.DecorationCoordinates decoration,
            ChunkPos drawChunk,
            BlockPos templatePos,
            net.minecraft.core.Vec3i size) {
        int min = Integer.MAX_VALUE;
        for (int x = templatePos.getX(); x < templatePos.getX() + size.getX(); x++) {
            for (int z = templatePos.getZ(); z < templatePos.getZ() + size.getZ(); z++) {
                min = Math.min(min, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z));
            }
        }
        int startDraw =
                placementStream(runtime, decoration, drawChunk).nextInt(BEACHED_HEIGHT_JITTER);
        return min - size.getY() / 2 - startDraw;
    }
}
