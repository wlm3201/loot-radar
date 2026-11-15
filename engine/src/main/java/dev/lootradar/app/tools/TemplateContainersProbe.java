package dev.lootradar.app.tools;

import dev.lootradar.engine.VanillaRuntime;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 诊断工具：读取结构模板，打印内嵌容器与数据标记，用于核对沉船快速路径的映射。 */
public final class TemplateContainersProbe {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        String structureId = args[1];
        var chunk =
                new net.minecraft.world.level.ChunkPos(
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        try (var runtime = VanillaRuntime.load(seed)) {
            var start = runtime.generateStructure(structureId, "minecraft:overworld", chunk);
            System.out.println("start pieces=" + start.getPieces().size());
            for (var piece : start.getPieces()) {
                if (!(piece instanceof PoolElementStructurePiece poolPiece)) {
                    System.out.println("  non-pool piece " + piece.getClass().getSimpleName());
                    continue;
                }
                var element = poolPiece.getElement();
                String templateId =
                        element
                                        instanceof
                                        net.minecraft.world.level.levelgen.structure.pools
                                                        .SinglePoolElement
                                                single
                                ? single.getTemplateLocation().toString()
                                : "?";
                if ("?".equals(templateId)) {
                    System.out.println("  element " + element.getClass().getSimpleName());
                    continue;
                }
                var template =
                        runtime.templateManager()
                                .get(net.minecraft.resources.Identifier.parse(templateId))
                                .orElse(null);
                System.out.println("piece " + templateId + " template=" + (template != null));
                if (template == null) continue;
                var settings = new StructurePlaceSettings().setRotation(poolPiece.getRotation());
                for (Block block :
                        new Block[] {Blocks.CHEST, Blocks.BARREL, Blocks.DECORATED_POT}) {
                    for (StructureTemplate.StructureBlockInfo info :
                            template.filterBlocks(BlockPos.ZERO, settings, block, true)) {
                        String table =
                                info.nbt() == null ? "-" : info.nbt().getStringOr("LootTable", "");
                        System.out.println(
                                "    "
                                        + block.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                                        + " at "
                                        + info.pos()
                                        + " nbt="
                                        + (info.nbt() != null)
                                        + " table="
                                        + (table.isEmpty() ? "(none)" : table));
                    }
                }
            }
        }
    }
}
