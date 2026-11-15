package dev.lootradar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * 战利品表抽奖器，对应原版两条随机源：
 *
 * <ul>
 *   <li>{@link #roll(String, long)}：容器自带 {@code lootTableSeed}，走 {@code withOptionalRandomSeed} →
 *       {@code RandomSource.create(seed)}（Legacy LCG）。
 *   <li>{@link #rollWithRandom(String, net.minecraft.util.RandomSource)}：宝库/实体等 random_sequence
 *       表，由调用方传入连续的序列随机源。
 * </ul>
 *
 * <p>{@code data/<version>/loot_tables/**} 中的 JSON 会覆盖原生表，用于跨版本复现。
 */
public final class VanillaLootOracle {

    private static final List<String> STAND_INS =
            List.of(
                    "minecraft:barrier",
                    "minecraft:structure_void",
                    "minecraft:light",
                    "minecraft:debug_stick",
                    "minecraft:command_block",
                    "minecraft:chain_command_block",
                    "minecraft:repeating_command_block",
                    "minecraft:jigsaw",
                    "minecraft:structure_block",
                    "minecraft:bedrock",
                    "minecraft:command_block_minecart");

    private record ParsedTable(LootTable table, Map<String, String> alias) {}

    private final VanillaRuntime runtime;
    private final Map<String, String> rawOverrides;
    private final Map<String, ParsedTable> overrideTables = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger parseFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.List<String> parseFailureLog =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public VanillaLootOracle(VanillaRuntime runtime) {
        this(runtime, Map.of());
    }

    public VanillaLootOracle(VanillaRuntime runtime, Map<String, String> rawOverrides) {
        this.runtime = runtime;
        this.rawOverrides = rawOverrides;
    }

    public List<LootStack> roll(String lootTableId, long lootTableSeed) {
        return roll(lootTableId, lootTableSeed, "minecraft:overworld", Vec3.ZERO);
    }

    public List<LootStack> roll(
            String lootTableId, long lootTableSeed, String dimensionId, Vec3 origin) {
        if (lootTableSeed == 0L) {
            throw new IllegalArgumentException("loot seed 0 is vanilla's unseeded sentinel");
        }
        ParsedTable parsed = parsed(lootTableId);
        LootContext context =
                new LootContext.Builder(params(dimensionId, origin))
                        .withOptionalRandomSeed(lootTableSeed)
                        .create(Optional.empty());
        return rawRoll(parsed.table(), context, parsed.alias());
    }

    public List<LootStack> rollWithRandom(String lootTableId, RandomSource random) {
        ParsedTable parsed = parsed(lootTableId);
        LootContext context =
                new LootContext.Builder(params())
                        .withOptionalRandomSource(random)
                        .create(Optional.empty());
        return rawRoll(parsed.table(), context, parsed.alias());
    }

    private static List<LootStack> rawRoll(
            LootTable table, LootContext context, Map<String, String> alias) {
        List<ItemStack> output = new ArrayList<>();
        table.getRandomItemsRaw(context, output::add);
        List<LootStack> result = new ArrayList<>();
        for (ItemStack stack : output) {
            if (!stack.isEmpty()) {
                String id =
                        net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(stack.getItem())
                                .toString();
                result.add(new LootStack(alias.getOrDefault(id, id), stack.getCount()));
            }
        }
        return List.copyOf(result);
    }

    private ParsedTable parsed(String lootTableId) {
        String raw = rawOverrides.get(lootTableId);
        if (raw != null) {
            return overrideTables.computeIfAbsent(lootTableId, id -> parseOverride(id, raw));
        }
        return new ParsedTable(registryTable(lootTableId), Map.of());
    }

    public LootTable table(String lootTableId) {
        return parsed(lootTableId).table();
    }

    public boolean hasOverride(String lootTableId) {
        return rawOverrides.containsKey(lootTableId);
    }

    private LootTable registryTable(String lootTableId) {
        ResourceKey<LootTable> key =
                ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(lootTableId));
        return runtime.reloadableResources().fullRegistries().getLootTable(key);
    }

    private ParsedTable parseOverride(String id, String raw) {
        try {
            JsonElement json = JsonParser.parseString(raw);
            Map<String, String> alias = new LinkedHashMap<>();
            rewriteUnknownItems(json, alias, new java.util.HashSet<>());
            RegistryOps<com.google.gson.JsonElement> ops =
                    RegistryOps.create(
                            com.mojang.serialization.JsonOps.INSTANCE, runtime.registries());
            LootTable table =
                    LootTable.DIRECT_CODEC
                            .parse(ops, json)
                            .getOrThrow(
                                    message ->
                                            new IllegalStateException(
                                                    "bad loot table override "
                                                            + id
                                                            + ": "
                                                            + message));
            return new ParsedTable(table, Map.copyOf(alias));
        } catch (RuntimeException broken) {
            parseFailures.incrementAndGet();
            parseFailureLog.add(id + ": " + broken);
            System.err.println(
                    "loot-radar: override "
                            + id
                            + " failed to parse, falling back to the runtime table: "
                            + broken);
            return new ParsedTable(registryTable(id), Map.of());
        }
    }

    public int overrideParseFailures() {
        return parseFailures.get();
    }

    public java.util.List<String> overrideParseFailureLog() {
        synchronized (parseFailureLog) {
            return java.util.List.copyOf(parseFailureLog);
        }
    }

    // 跨版本覆盖表里可能出现当前原生注册表没有的物品：临时替换成占位物品，
    // 抽奖后再用 alias 映射回原始 id，避免 codec 解析失败。
    private void rewriteUnknownItems(
            JsonElement node, Map<String, String> alias, java.util.Set<String> used) {
        if (node instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (entry.getKey().equals("name")
                        && value.isJsonPrimitive()
                        && value.getAsString().contains(":")) {
                    String name = value.getAsString();
                    boolean known =
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(
                                    Identifier.parse(name));
                    if (!known) {
                        String standIn = null;
                        for (String candidate : STAND_INS) {
                            if (used.add(candidate)) {
                                standIn = candidate;
                                break;
                            }
                        }
                        if (standIn == null) {
                            throw new IllegalStateException("stand-in pool exhausted for " + name);
                        }
                        alias.put(standIn, name);
                        object.addProperty("name", standIn);
                    }
                } else {
                    rewriteUnknownItems(value, alias, used);
                }
            }
        } else if (node instanceof JsonArray array) {
            for (JsonElement element : array) {
                rewriteUnknownItems(element, alias, used);
            }
        }
    }

    // 统一用 CHEST 参数集与中心原点：目标物品的表不依赖实体/工具参数，
    // 这样既能复现箱子，也能复现宝库/实体序列的随机消耗。
    private LootParams params() {
        return params("minecraft:overworld", Vec3.ZERO);
    }

    private LootParams params(String dimensionId, Vec3 origin) {
        return new LootParams.Builder(runtime.contextualLevel(dimensionId))
                .withParameter(LootContextParams.ORIGIN, origin)
                .create(LootContextParamSets.CHEST);
    }
}
