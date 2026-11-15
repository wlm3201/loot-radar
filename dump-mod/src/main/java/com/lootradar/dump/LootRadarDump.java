package com.lootradar.dump;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.slf4j.Logger;

/**
 * dump-mod 入口：注册容器快照、结构源普查、Bounce 矿井普查三条真值生产线，
 * 并通过 {@code -Dlootradar.*} 系统属性在服务器启动后自动跑完并 halt，供离线脚本采集。
 */
public final class LootRadarDump implements ModInitializer {

    public static final String MOD_ID = "lootradar";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        BounceCoverageDump.register();
        SourceCoverageDump.register();
        CommandRegistrationCallback.EVENT.register(LootRadarDump::register);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                LootRadarDump::autoDump);
        LOGGER.info("loot-radar dump ready: /lootradar containers | /lootradar sequence");
    }

    private static void autoDump(net.minecraft.server.MinecraftServer server) {
        String radiusValue = System.getProperty("lootradar.autoDump", "");
        if (radiusValue.isEmpty()) {
            return;
        }
        int radius = Integer.parseInt(radiusValue);
        String lootingKills = System.getProperty("lootradar.lootingCalibration", "");
        if (!lootingKills.isEmpty()) {
            try {
                LootDumpCommand.calibrateLooting(
                        server.createCommandSourceStack(),
                        Integer.parseInt(lootingKills),
                        System.getProperty("lootradar.out", null));
            } catch (RuntimeException exception) {
                LOGGER.error("auto looting calibration failed", exception);
            }
            server.execute(() -> server.halt(false));
            return;
        }
        String sequenceTable = System.getProperty("lootradar.sequence", "");
        if (!sequenceTable.isEmpty()) {
            String kind = System.getProperty("lootradar.seqKind", "chest");
            int count = Integer.parseInt(System.getProperty("lootradar.seqN", "100"));
            String out = System.getProperty("lootradar.out", null);
            try {
                LootDumpCommand.dumpSequence(
                        server.createCommandSourceStack(), kind, sequenceTable, count, out);
            } catch (RuntimeException exception) {
                LOGGER.error("auto sequence dump failed", exception);
            }
            server.execute(() -> server.halt(false));
            return;
        }
        net.minecraft.server.level.ServerLevel level = server.overworld();
        LOGGER.info("loot-radar auto-dump radius {} around 0,0", radius);
        try {
            java.nio.file.Path out =
                    LootDumpCommand.writeContainers(
                            level, net.minecraft.core.BlockPos.ZERO, radius, null);
            LOGGER.info("loot-radar auto-dump written to {}", out.toAbsolutePath());
        } catch (RuntimeException exception) {
            LOGGER.error("auto-dump failed", exception);
        }
        server.execute(() -> server.halt(false));
    }

    private static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            net.minecraft.commands.CommandBuildContext context,
            Commands.CommandSelection environment) {
        LootDumpCommand.registerSequenceKinds(dispatcher);
        LootDumpCommand.registerLootingCalibration(dispatcher);
        dispatcher.register(
                Commands.literal("lootradar")
                        .then(
                                Commands.literal("containers")
                                        .then(
                                                Commands.argument(
                                                                "x",
                                                                net.minecraft.commands.arguments
                                                                        .coordinates
                                                                        .BlockPosArgument
                                                                        .blockPos())
                                                        .then(
                                                                Commands.argument(
                                                                                "radius",
                                                                                com.mojang.brigadier
                                                                                        .arguments
                                                                                        .IntegerArgumentType
                                                                                        .integer(
                                                                                                1,
                                                                                                2048))
                                                                        .executes(
                                                                                source ->
                                                                                        LootDumpCommand
                                                                                                .dumpContainers(
                                                                                                        source
                                                                                                                .getSource(),
                                                                                                        net
                                                                                                                .minecraft
                                                                                                                .commands
                                                                                                                .arguments
                                                                                                                .coordinates
                                                                                                                .BlockPosArgument
                                                                                                                .getLoadedBlockPos(
                                                                                                                        source,
                                                                                                                        "x"),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        source,
                                                                                                                        "radius"),
                                                                                                        null))
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "path",
                                                                                                com
                                                                                                        .mojang
                                                                                                        .brigadier
                                                                                                        .arguments
                                                                                                        .StringArgumentType
                                                                                                        .greedyString())
                                                                                        .executes(
                                                                                                source ->
                                                                                                        LootDumpCommand
                                                                                                                .dumpContainers(
                                                                                                                        source
                                                                                                                                .getSource(),
                                                                                                                        net
                                                                                                                                .minecraft
                                                                                                                                .commands
                                                                                                                                .arguments
                                                                                                                                .coordinates
                                                                                                                                .BlockPosArgument
                                                                                                                                .getLoadedBlockPos(
                                                                                                                                        source,
                                                                                                                                        "x"),
                                                                                                                        com
                                                                                                                                .mojang
                                                                                                                                .brigadier
                                                                                                                                .arguments
                                                                                                                                .IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        source,
                                                                                                                                        "radius"),
                                                                                                                        com
                                                                                                                                .mojang
                                                                                                                                .brigadier
                                                                                                                                .arguments
                                                                                                                                .StringArgumentType
                                                                                                                                .getString(
                                                                                                                                        source,
                                                                                                                                        "path")))))))
                        .then(
                                Commands.literal("sequence")
                                        .then(
                                                Commands.argument(
                                                                "table",
                                                                com.mojang.brigadier.arguments
                                                                        .StringArgumentType
                                                                        .string())
                                                        .then(
                                                                Commands.argument(
                                                                                "count",
                                                                                com.mojang.brigadier
                                                                                        .arguments
                                                                                        .IntegerArgumentType
                                                                                        .integer(
                                                                                                1,
                                                                                                10000))
                                                                        .executes(
                                                                                source ->
                                                                                        LootDumpCommand
                                                                                                .dumpSequence(
                                                                                                        source
                                                                                                                .getSource(),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .StringArgumentType
                                                                                                                .getString(
                                                                                                                        source,
                                                                                                                        "table"),
                                                                                                        com
                                                                                                                .mojang
                                                                                                                .brigadier
                                                                                                                .arguments
                                                                                                                .IntegerArgumentType
                                                                                                                .getInteger(
                                                                                                                        source,
                                                                                                                        "count"),
                                                                                                        null))
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "path",
                                                                                                com
                                                                                                        .mojang
                                                                                                        .brigadier
                                                                                                        .arguments
                                                                                                        .StringArgumentType
                                                                                                        .greedyString())
                                                                                        .executes(
                                                                                                source ->
                                                                                                        LootDumpCommand
                                                                                                                .dumpSequence(
                                                                                                                        source
                                                                                                                                .getSource(),
                                                                                                                        com
                                                                                                                                .mojang
                                                                                                                                .brigadier
                                                                                                                                .arguments
                                                                                                                                .StringArgumentType
                                                                                                                                .getString(
                                                                                                                                        source,
                                                                                                                                        "table"),
                                                                                                                        com
                                                                                                                                .mojang
                                                                                                                                .brigadier
                                                                                                                                .arguments
                                                                                                                                .IntegerArgumentType
                                                                                                                                .getInteger(
                                                                                                                                        source,
                                                                                                                                        "count"),
                                                                                                                        com
                                                                                                                                .mojang
                                                                                                                                .brigadier
                                                                                                                                .arguments
                                                                                                                                .StringArgumentType
                                                                                                                                .getString(
                                                                                                                                        source,
                                                                                                                                        "path"))))))));
    }
}
