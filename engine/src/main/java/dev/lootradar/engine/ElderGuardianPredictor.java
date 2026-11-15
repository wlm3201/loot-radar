package dev.lootradar.engine;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequence;

/**
 * 远古守卫者掉落的手工重放模型，严格按 {@code entities/elder_guardian} 战利品表 的池顺序消耗 {@code random_sequence} 随机数：海晶碎片
 * 0..2 + 抢夺加成、 权重 3/2/1 的鳕鱼/海晶砂粒/空、湿海绵、带抢夺加成的钓鱼表判定、 权重 4/1 的潮汐盔甲纹饰模板。
 *
 * <p>已对照 {@code VanillaEntityLoot.elderGuardianLootTable()} 与 {@code
 * EnchantedCountIncreaseFunction}（{@code Math.round(level * nextFloat())}） 核实；{@code rollKill}
 * 的抽取顺序与数量不能调整，否则第 n 次击杀会整体错位。
 */
public final class ElderGuardianPredictor {

    public static final String TABLE = "minecraft:entities/elder_guardian";
    public static final String SEQUENCE_KEY = "minecraft:entities/elder_guardian";
    public static final String FISH_TABLE = "minecraft:gameplay/fishing/fish";
    public static final String TIDE_TEMPLATE = "minecraft:tide_armor_trim_smithing_template";

    public record KillResult(int nthKill, List<LootStack> items) {
        public boolean contains(String itemId) {
            return items.stream().anyMatch(stack -> stack.itemId().equals(itemId));
        }
    }

    private ElderGuardianPredictor() {}

    public static List<KillResult> findHits(
            VanillaLootOracle oracle,
            long worldSeed,
            int lootingLevel,
            int startOffset,
            int maxKills,
            String targetItemId) {
        if (lootingLevel < 0 || lootingLevel > 3) {
            throw new IllegalArgumentException("looting level must be 0..3");
        }
        if (startOffset < 1) {
            throw new IllegalArgumentException("startOffset must be >= 1");
        }
        RandomSource stream =
                new RandomSequence(worldSeed, Identifier.parse(SEQUENCE_KEY)).random();
        for (int i = 1; i < startOffset; i++) {
            rollKill(oracle, stream, lootingLevel);
        }
        List<KillResult> hits = new ArrayList<>();
        for (int kill = startOffset; kill < startOffset + maxKills; kill++) {
            List<LootStack> items = rollKill(oracle, stream, lootingLevel);
            KillResult result = new KillResult(kill, items);
            if (result.contains(targetItemId)) {
                hits.add(result);
            }
        }
        return List.copyOf(hits);
    }

    public static List<LootStack> rollKill(
            VanillaLootOracle oracle, RandomSource random, int lootingLevel) {
        List<LootStack> items = new ArrayList<>();

        int shardCount = mthNextInt(random, 0, 2);
        if (lootingLevel > 0) {
            shardCount += Math.round(lootingLevel * mthNextFloat(random, 0.0f, 1.0f));
        }
        if (shardCount > 0) {
            items.add(new LootStack("minecraft:prismarine_shard", shardCount));
        }

        int pick = random.nextInt(6);
        if (pick < 3) {
            int count = 1;
            if (lootingLevel > 0) {
                count += Math.round(lootingLevel * mthNextFloat(random, 0.0f, 1.0f));
            }
            items.add(new LootStack("minecraft:cod", count));
        } else if (pick < 5) {
            int count = 1;
            if (lootingLevel > 0) {
                count += Math.round(lootingLevel * mthNextFloat(random, 0.0f, 1.0f));
            }
            items.add(new LootStack("minecraft:prismarine_crystals", count));
        }

        items.add(new LootStack("minecraft:wet_sponge", 1));

        float chance = lootingLevel > 0 ? 0.035f + 0.01f * (lootingLevel - 1) : 0.025f;
        if (random.nextFloat() < chance) {
            items.addAll(oracle.rollWithRandom(FISH_TABLE, random));
        }

        if (random.nextInt(5) == 4) {
            items.add(new LootStack(TIDE_TEMPLATE, 1));
        }

        return List.copyOf(items);
    }

    private static int mthNextInt(RandomSource random, int minInclusive, int maxInclusive) {
        return minInclusive >= maxInclusive
                ? minInclusive
                : random.nextInt(maxInclusive - minInclusive + 1) + minInclusive;
    }

    private static float mthNextFloat(RandomSource random, float min, float max) {
        return min >= max ? min : random.nextFloat() * (max - min) + min;
    }
}
