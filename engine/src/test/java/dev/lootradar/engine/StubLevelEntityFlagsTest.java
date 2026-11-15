package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;

class StubLevelEntityFlagsTest {

    @Test
    void suppressingLevelEnablesVanillaEntities() {
        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            assertTrue(
                    FeatureFlags.VANILLA_SET.isSubsetOf(
                            runtime.suppressingServerLevel().enabledFeatures()),
                    "EntityType.canSpawn() 依赖 enabledFeatures()，空集合会跳过所有原版实体");
            assertTrue(
                    FeatureFlags.VANILLA_SET.isSubsetOf(
                            runtime.contextualLevel("minecraft:the_nether").enabledFeatures()));
        }
    }

    @Test
    void threadLocalTemplatesDropEntityLists() {
        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            // 堡垒遗迹的 mobs 模板原本带 piglin 实体；预测只需要方块与方块实体。
            for (String id :
                    List.of(
                            "minecraft:bastion/mobs/melee_piglin",
                            "minecraft:bastion/mobs/crossbow_piglin")) {
                StructureTemplate template =
                        runtime.templateManager().get(Identifier.parse(id)).orElseThrow();
                CompoundTag saved = template.save(new CompoundTag());
                assertTrue(
                        saved.getListOrEmpty("entities").isEmpty(),
                        id + " 仍带有实体列表，放置时会打印 Skipping Entity WARN");
            }
        }
    }
}
