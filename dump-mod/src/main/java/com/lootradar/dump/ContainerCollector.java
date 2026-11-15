package com.lootradar.dump;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 从真实已生成的区块中收集「真值」容器：方块实体（普通容器/陶罐/可疑沙子/宝库）
 * 与箱子矿车，并用各自的 lootTableSeed 复算内容，供离线对比引擎预测结果。
 */
final class ContainerCollector {

    private ContainerCollector() {}

    static List<Map<String, Object>> collect(ServerLevel level, BlockPos center, int radius) {
        List<Map<String, Object>> result = new ArrayList<>();
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    Map<String, Object> snapshot =
                            snapshot(level, entry.getKey(), entry.getValue());
                    if (snapshot != null) {
                        result.add(snapshot);
                    }
                }
            }
        }
        for (var cart :
                level.getEntities(
                        net.minecraft.world.level.entity.EntityTypeTest.forClass(
                                net.minecraft.world.entity.vehicle.minecart.MinecartChest.class),
                        entity ->
                                entity.getX() >= minChunkX * 16
                                        && entity.getX() < (maxChunkX + 1) * 16
                                        && entity.getZ() >= minChunkZ * 16
                                        && entity.getZ() < (maxChunkZ + 1) * 16)) {
            var table = cart.getContainerLootTable();
            if (table == null) continue;
            BlockPos pos = cart.blockPosition();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pos", List.of(pos.getX(), pos.getY(), pos.getZ()));
            row.put("origin", List.of(cart.getX(), cart.getY(), cart.getZ()));
            row.put("block", "minecraft:chest_minecart");
            row.put("uuid", cart.getStringUUID());
            row.put("kind", "container");
            row.put("table", table.identifier().toString());
            row.put("seed", cart.getContainerLootTableSeed());
            row.put("biome", level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString());
            var items =
                    fillItems(
                            level,
                            table,
                            cart.getContainerLootTableSeed(),
                            cart.position(),
                            net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
                                    .CHEST);
            if (items != null) row.put("items", items);
            result.add(row);
        }
        return result;
    }

    static Map<String, Object> snapshot(ServerLevel level, BlockPos pos, BlockEntity blockEntity) {
        String blockId =
                BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).toString();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pos", List.of(pos.getX(), pos.getY(), pos.getZ()));
        row.put("block", blockId);

        if (blockEntity instanceof DecoratedPotBlockEntity pot) {
            List<String> sherds = new ArrayList<>();
            for (Item item : PotItems.ordered(pot.getDecorations())) {
                sherds.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
            row.put("kind", "pot_sherd");
            row.put("sherds", sherds);
            if (pot.getLootTable() != null) {
                row.put("kind", "corridor_pot");
                row.put("table", pot.getLootTable().identifier().toString());
                row.put("seed", pot.getLootTableSeed());
                List<Map<String, Object>> items =
                        fillItems(
                                level,
                                pot.getLootTable(),
                                pot.getLootTableSeed(),
                                pos,
                                net.minecraft.world.level.storage.loot.parameters
                                        .LootContextParamSets.CHEST);
                if (items != null) {
                    row.put("items", items);
                }
            }
            return row;
        }

        if (blockEntity instanceof BrushableBlockEntity brushable) {
            var tag = brushable.saveWithFullMetadata(level.registryAccess());
            String table = tag.getStringOr("LootTable", "");
            if (table.isEmpty()) {
                return null;
            }
            row.put("kind", "archaeology");
            row.put("table", table);
            row.put("seed", tag.getLongOr("LootTableSeed", 0L));
            List<Map<String, Object>> items =
                    fillItems(
                            level,
                            net.minecraft.resources.ResourceKey.create(
                                    net.minecraft.core.registries.Registries.LOOT_TABLE,
                                    net.minecraft.resources.Identifier.parse(table)),
                            tag.getLongOr("LootTableSeed", 0L),
                            pos,
                            net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
                                    .ARCHAEOLOGY);
            if (items != null) {
                row.put("items", items);
            }
            return row;
        }

        if (blockEntity instanceof VaultBlockEntity vault) {
            row.put("kind", "vault");
            row.put("table", vault.getConfig().lootTable().identifier().toString());
            row.put("seed", 0L);
            return row;
        }

        if (blockEntity instanceof RandomizableContainer container) {
            if (container.getLootTable() == null) {
                return null;
            }
            row.put("kind", "container");
            row.put("table", container.getLootTable().identifier().toString());
            row.put("seed", container.getLootTableSeed());
            if (container instanceof net.minecraft.world.Container inventory) {
                row.put("capacity", inventory.getContainerSize());
            }
            List<Map<String, Object>> items =
                    fillItems(
                            level,
                            container.getLootTable(),
                            container.getLootTableSeed(),
                            pos,
                            net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
                                    .CHEST);
            if (items != null) {
                row.put("items", items);
            }
            return row;
        }

        return null;
    }

    private static List<Map<String, Object>> fillItems(
            ServerLevel level,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable>
                    tableKey,
            long seed,
            BlockPos pos,
            net.minecraft.util.context.ContextKeySet paramSet) {
        return fillItems(
                level, tableKey, seed, net.minecraft.world.phys.Vec3.atCenterOf(pos), paramSet);
    }

    private static List<Map<String, Object>> fillItems(
            ServerLevel level,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable>
                    tableKey,
            long seed,
            net.minecraft.world.phys.Vec3 origin,
            net.minecraft.util.context.ContextKeySet paramSet) {
        if (seed == 0) {
            return null;
        }
        var table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        var builder =
                new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                        .withParameter(
                                net.minecraft.world.level.storage.loot.parameters.LootContextParams
                                        .ORIGIN,
                                origin);
        if (paramSet
                == net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
                        .ARCHAEOLOGY) {
            builder.withParameter(
                    net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL,
                    new net.minecraft.world.item.ItemStack(
                            net.minecraft.world.item.Items.STONE_HOE));
            var stand =
                    BuiltInRegistries.ENTITY_TYPE
                            .getValue(
                                    net.minecraft.resources.Identifier.parse(
                                            "minecraft:armor_stand"))
                            .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            builder.withParameter(
                    net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY,
                    stand);
        }
        var params = builder.create(paramSet);
        var context =
                new net.minecraft.world.level.storage.loot.LootContext.Builder(params)
                        .withOptionalRandomSeed(seed)
                        .create(java.util.Optional.of(tableKey.identifier()));
        List<Map<String, Object>> items = new ArrayList<>();
        List<net.minecraft.world.item.ItemStack> output = new ArrayList<>();
        table.getRandomItemsRaw(context, output::add);
        for (net.minecraft.world.item.ItemStack stack : output) {
            if (!stack.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                item.put("count", stack.getCount());
                items.add(item);
            }
        }
        return items;
    }
}
