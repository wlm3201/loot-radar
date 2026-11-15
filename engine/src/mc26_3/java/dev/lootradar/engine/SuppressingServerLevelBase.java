package dev.lootradar.engine;

import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

abstract class SuppressingServerLevelBase extends ServerLevel {
    SuppressingServerLevelBase() {
        super(
                (MinecraftServer) null,
                (Executor) null,
                (LevelStorageSource.LevelStorageAccess) null,
                (ServerLevelData) null,
                (ResourceKey<Level>) null,
                (LevelStem) null,
                false,
                0L,
                List.<CustomSpawner>of(),
                false);
    }
}
