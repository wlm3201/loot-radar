package dev.lootradar.engine;

import com.mojang.datafixers.DataFixer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/** 为并发扫描提供「每线程一份」的结构模板副本，避免多个放置任务共享并修改同一个 {@code StructureTemplate} 实例。模板通过 NBT 序列化往返来深拷贝。 */
public final class ThreadLocalTemplates extends StructureTemplateManager {

    private final ThreadLocal<Map<Identifier, StructureTemplate>> perThread =
            ThreadLocal.withInitial(HashMap::new);
    private final ResourceManager resourceManager;
    private final LevelStorageSource.LevelStorageAccess storageAccess;
    private final DataFixer dataFixer;
    private final HolderGetter<Block> blockLookup;
    private StructureTemplateManager fallback;

    public ThreadLocalTemplates(
            ResourceManager resourceManager,
            LevelStorageSource.LevelStorageAccess storageAccess,
            DataFixer dataFixer,
            HolderGetter<Block> blockLookup) {
        super(resourceManager, storageAccess, dataFixer, blockLookup);
        this.resourceManager = resourceManager;
        this.storageAccess = storageAccess;
        this.dataFixer = dataFixer;
        this.blockLookup = blockLookup;
    }

    // 先查线程本地副本；未命中则从共享模板深拷贝一份，绝不把共享实例交给放置代码。
    @Override
    public Optional<StructureTemplate> get(Identifier id) {
        Map<Identifier, StructureTemplate> local = perThread.get();
        StructureTemplate cached = local.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<StructureTemplate> shared = super.get(id);
        if (shared.isEmpty()) {
            shared = fallback().get(id);
        }
        if (shared.isEmpty()) {
            return shared;
        }
        StructureTemplate copy = copyTemplate(shared.get());
        local.put(id, copy);
        return Optional.of(copy);
    }

    public StructureTemplate getOrCreate(Identifier id) {
        return get(id).orElseGet(
                        () -> MinecraftVersionAdapter.loadTemplate(blockLookup, new CompoundTag()));
    }

    private synchronized StructureTemplateManager fallback() {
        if (fallback == null) {
            fallback =
                    new StructureTemplateManager(
                            resourceManager, storageAccess, dataFixer, blockLookup);
        }
        return fallback;
    }

    private StructureTemplate copyTemplate(StructureTemplate source) {
        CompoundTag tag = source.save(new CompoundTag());
        tag.remove("entities");
        return MinecraftVersionAdapter.loadTemplate(blockLookup, tag);
    }
}
