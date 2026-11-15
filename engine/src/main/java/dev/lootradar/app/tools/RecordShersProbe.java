package dev.lootradar.app.tools;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.StructureScanner;
import dev.lootradar.engine.VanillaRuntime;

/** 诊断工具：扫描单个结构起点并打印陶罐纹样与 pots/* 战利品表记录。 */
public final class RecordShersProbe {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        String structureId = args[1];
        var chunk =
                new net.minecraft.world.level.ChunkPos(
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]));
        try (var runtime = VanillaRuntime.load(seed)) {
            var version = GameVersion.parse(net.minecraft.SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var start = runtime.generateStructure(structureId, "minecraft:overworld", chunk);
            var sources =
                    StructureScanner.scan(
                            runtime, structureId, "minecraft:overworld", start, null, registry);
            System.out.println("records=" + sources.size());
            int shown = 0;
            for (var source : sources) {
                if (source.potSherds().isEmpty() && !source.lootTable().contains("pots/")) continue;
                System.out.println(
                        "  "
                                + source.pos()
                                + " kind="
                                + source.kind()
                                + " table="
                                + (source.lootTable().isEmpty() ? "-" : source.lootTable())
                                + " sherds="
                                + source.potSherds());
                if (++shown >= 12) break;
            }
        }
    }
}
