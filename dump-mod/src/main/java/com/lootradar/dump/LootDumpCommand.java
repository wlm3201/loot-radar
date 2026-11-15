package com.lootradar.dump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * 实现 /lootradar 的各条命令：容器快照、战利品表序列、抢夺等级校准，
 * 以及自动 dump 使用的写出逻辑。输出 schema 均以 {@code loot-radar-*} 开头。
 */
final class LootDumpCommand {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LootDumpCommand() {}

    static int dumpContainers(CommandSourceStack source, BlockPos center, int radius, String path) {
        Path out = writeContainers(source.getLevel(), center, radius, path);
        source.sendSuccess(
                () ->
                        net.minecraft.network.chat.Component.literal(
                                "loot-radar dumped containers -> " + out.toAbsolutePath()),
                true);
        return 1;
    }

    static Path writeContainers(ServerLevel level, BlockPos center, int radius, String path) {
        List<Map<String, Object>> containers = ContainerCollector.collect(level, center, radius);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "loot-radar-dump/2");
        root.put("version", level.getServer().getServerModName());
        root.put("seed", level.getSeed());
        root.put("dimension", level.dimension().identifier().toString());
        root.put("center", List.of(center.getX(), center.getY(), center.getZ()));
        root.put("radius", radius);
        root.put("snapshot", "after_full_generation_before_ticks");
        root.put("complete", true);
        root.put("containers", containers);
        Path out =
                resolve(
                        path,
                        "containers-"
                                + level.dimension().identifier().getPath()
                                + "-"
                                + center.getX()
                                + "_"
                                + center.getZ()
                                + ".json");
        write(out, root, containers.size() + " containers");
        return out;
    }

    static int dumpSequence(CommandSourceStack source, String tableId, int count, String path) {
        return dumpSequence(source, "chest", tableId, count, path);
    }

    static int dumpSequence(
            CommandSourceStack source, String kind, String tableId, int count, String path) {
        ServerLevel level = source.getLevel();
        Identifier key = Identifier.parse(tableId.contains(":") ? tableId : "minecraft:" + tableId);
        net.minecraft.resources.ResourceKey<LootTable> tableKey =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LOOT_TABLE, key);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        LootParams params = sequenceParams(level, source, kind);
        List<Map<String, Object>> rolls = new ArrayList<>();
        for (int nth = 1; nth <= count; nth++) {
            LootContext context = new LootContext.Builder(params).create(Optional.of(key));
            List<Map<String, Object>> items = new ArrayList<>();
            List<ItemStack> output = new ArrayList<>();
            table.getRandomItemsRaw(context, output::add);
            for (ItemStack stack : output) {
                if (!stack.isEmpty()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put(
                            "item",
                            net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(stack.getItem())
                                    .toString());
                    item.put("count", stack.getCount());
                    items.add(item);
                }
            }
            Map<String, Object> roll = new LinkedHashMap<>();
            roll.put("nth", nth);
            roll.put("items", items);
            rolls.add(roll);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "loot-radar-sequence/2");
        root.put("kind", kind);
        root.put("seed", level.getSeed());
        root.put("table", key.toString());
        root.put("rolls", rolls);
        Path out = resolve(path, "sequence-" + key.getPath().replace('/', '_') + ".json");
        write(out, root, rolls.size() + " sequence rolls");
        source.sendSuccess(
                () ->
                        net.minecraft.network.chat.Component.literal(
                                "loot-radar dumped "
                                        + rolls.size()
                                        + " sequence rolls -> "
                                        + out.toAbsolutePath()),
                true);
        return rolls.size();
    }

    static void registerSequenceKinds(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var tableType = com.mojang.brigadier.arguments.StringArgumentType.string();
        var countType = com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10000);
        // chest 由主命令 /lootradar sequence <table> <count> 覆盖，重复注册会造成语法争用。
        for (String kind : List.of("entity", "vault", "fishing", "barter")) {
            var pathArg = net.minecraft.commands.Commands.argument(
                    "path", com.mojang.brigadier.arguments.StringArgumentType.greedyString());
            var countArg = net.minecraft.commands.Commands.argument("count", countType);
            countArg.executes(source -> dumpSequence(
                            source.getSource(), kind,
                            com.mojang.brigadier.arguments.StringArgumentType.getString(source, "table"),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(source, "count"),
                            null))
                    .then(pathArg.executes(source -> dumpSequence(
                            source.getSource(), kind,
                            com.mojang.brigadier.arguments.StringArgumentType.getString(source, "table"),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(source, "count"),
                            com.mojang.brigadier.arguments.StringArgumentType.getString(source, "path"))));
            dispatcher.register(
                    net.minecraft.commands.Commands.literal("lootradar")
                            .then(net.minecraft.commands.Commands.literal("sequence")
                                    .then(net.minecraft.commands.Commands.literal(kind)
                                            .then(net.minecraft.commands.Commands.argument("table", tableType)
                                                    .then(countArg)))));
        }
    }

    static int calibrateLooting(CommandSourceStack source, int killsPerLevel, String path) {
        ServerLevel level = source.getLevel();
        Identifier key = Identifier.parse("minecraft:entities/elder_guardian");
        net.minecraft.resources.ResourceKey<LootTable> tableKey =
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.LOOT_TABLE, key);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> looting =
                level.registryAccess()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOOTING);
        List<Map<String, Object>> rows = new ArrayList<>();
        int nth = 0;
        for (int lootingLevel = 0; lootingLevel <= 3; lootingLevel++) {
            var killer =
                    new net.minecraft.server.level.ServerPlayer(
                            level.getServer(),
                            level,
                            new com.mojang.authlib.GameProfile(
                                    java.util.UUID.randomUUID(), "lootradar-calibration"),
                            net.minecraft.server.level.ClientInformation.createDefault());
            if (lootingLevel > 0) {
                ItemStack sword = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
                sword.enchant(looting, lootingLevel);
                killer.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, sword);
            }
            for (int k = 0; k < killsPerLevel; k++) {
                nth++;
                var victim =
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                                .getValue(net.minecraft.resources.Identifier.parse("minecraft:elder_guardian"))
                                .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                LootParams params =
                        new LootParams.Builder(level)
                                .withParameter(LootContextParams.THIS_ENTITY, victim)
                                .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer)
                                .withParameter(LootContextParams.ATTACKING_ENTITY, killer)
                                .withParameter(LootContextParams.ORIGIN, source.getPosition())
                                .withParameter(
                                        LootContextParams.DAMAGE_SOURCE,
                                        level.damageSources().playerAttack(killer))
                                .create(LootContextParamSets.ENTITY);
                LootContext context = new LootContext.Builder(params).create(Optional.of(key));
                List<Map<String, Object>> items = new ArrayList<>();
                List<ItemStack> output = new ArrayList<>();
                table.getRandomItemsRaw(context, output::add);
                for (ItemStack stack : output) {
                    if (!stack.isEmpty()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put(
                                "item",
                                net.minecraft.core.registries.BuiltInRegistries.ITEM
                                        .getKey(stack.getItem())
                                        .toString());
                        item.put("count", stack.getCount());
                        items.add(item);
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nth", nth);
                row.put("looting", lootingLevel);
                row.put("items", items);
                rows.add(row);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "loot-radar-looting/1");
        root.put("seed", level.getSeed());
        root.put("table", key.toString());
        root.put("killsPerLevel", killsPerLevel);
        root.put("kills", rows);
        Path out = resolve(path, "looting-calibration.json");
        write(out, root, rows.size() + " looting kills");
        source.sendSuccess(
                () ->
                        net.minecraft.network.chat.Component.literal(
                                "loot-radar looting calibration -> " + out.toAbsolutePath()),
                true);
        return rows.size();
    }

    static void registerLootingCalibration(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        var killsArg =
                net.minecraft.commands.Commands.argument(
                        "killsPerLevel",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10000));
        killsArg.executes(
                        source ->
                                calibrateLooting(
                                        source.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                source, "killsPerLevel"),
                                        null))
                .then(
                        net.minecraft.commands.Commands.argument(
                                        "path", com.mojang.brigadier.arguments.StringArgumentType.string())
                                .executes(
                                        source ->
                                                calibrateLooting(
                                                        source.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                                source, "killsPerLevel"),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(
                                                                source, "path"))));
        dispatcher.register(
                net.minecraft.commands.Commands.literal("lootradar")
                        .then(
                                net.minecraft.commands.Commands.literal("looting-calibrate")
                                        .then(killsArg)));
    }

    private static net.minecraft.world.entity.Entity sampleEntity(
            ServerLevel level, CommandSourceStack source) {
        var stand =
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getValue(net.minecraft.resources.Identifier.parse("minecraft:armor_stand"))
                        .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (stand == null) {
            throw new IllegalStateException("cannot create sample entity");
        }
        stand.snapTo(source.getPosition());
        return stand;
    }

    private static LootParams sequenceParams(
            ServerLevel level, CommandSourceStack source, String kind) {
        LootParams.Builder builder = new LootParams.Builder(level);
        switch (kind) {
            case "entity": {
                return builder
                        .withParameter(LootContextParams.THIS_ENTITY, sampleEntity(level, source))
                        .withParameter(LootContextParams.ORIGIN, source.getPosition())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                        .create(LootContextParamSets.ENTITY);
            }
            case "vault":
                return builder
                        .withParameter(LootContextParams.ORIGIN, source.getPosition())
                        .create(LootContextParamSets.VAULT);
            case "fishing":
                return builder
                        .withParameter(LootContextParams.ORIGIN, source.getPosition())
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .create(LootContextParamSets.FISHING);
            case "barter": {
                return builder
                        .withParameter(LootContextParams.THIS_ENTITY, sampleEntity(level, source))
                        .create(LootContextParamSets.PIGLIN_BARTER);
            }
            default:
                return builder
                        .withParameter(LootContextParams.ORIGIN, source.getPosition())
                        .create(LootContextParamSets.CHEST);
        }
    }

    private static Path resolve(String path, String fallbackName) {
        return path == null ? Path.of("loot-radar-dump").resolve(fallbackName) : Path.of(path);
    }

    private static void write(Path out, Map<String, Object> root, String summary) {
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, GSON.toJson(root), StandardCharsets.UTF_8);
            LootRadarDump.LOGGER.info("loot-radar dumped {} -> {}", summary, out.toAbsolutePath());
        } catch (IOException exception) {
            throw new IllegalStateException("dump failed", exception);
        }
    }
}
