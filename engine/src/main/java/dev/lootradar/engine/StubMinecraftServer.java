package dev.lootradar.engine;

import java.io.IOException;
import net.minecraft.SystemReport;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.debugchart.SampleLogger;
import org.objenesis.ObjenesisStd;

/** 同上：{@code LootContext.create()} 会访问 server 的注册表与模板管理器，这里给出空壳实现。 */
final class StubMinecraftServer extends VersionedMinecraftServer {

    private static final ObjenesisStd OBJENESIS = new ObjenesisStd();
    private ReloadableServerRegistries.Holder registries;
    private net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
            templates;

    void setTemplates(
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
                    templates) {
        this.templates = templates;
    }

    public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
            getStructureManager() {
        return templates;
    }

    public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
            getStructureTemplateManager() {
        return templates;
    }

    private StubMinecraftServer() {
        super();
    }

    static StubMinecraftServer create(ReloadableServerRegistries.Holder registries) {
        StubMinecraftServer server = OBJENESIS.newInstance(StubMinecraftServer.class);
        server.registries = registries;
        return server;
    }

    @Override
    public ReloadableServerRegistries.Holder reloadableRegistries() {
        return registries;
    }

    @Override
    protected boolean initServer() throws IOException {
        return true;
    }

    @Override
    public LevelBasedPermissionSet operatorUserPermissions() {
        return LevelBasedPermissionSet.ALL;
    }

    @Override
    public PermissionSet getFunctionCompilationPermissions() {
        return PermissionSet.NO_PERMISSIONS;
    }

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    protected SampleLogger getTickTimeLogger() {
        return null;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }

    @Override
    public SystemReport fillServerSystemReport(SystemReport report) {
        return report;
    }

    @Override
    public boolean isDedicatedServer() {
        return false;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    public int getCommandSpamThresholdSeconds() {
        return 0;
    }

    public int getChatSpamThresholdSeconds() {
        return 0;
    }

    @Override
    public boolean useNativeTransport() {
        return false;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(NameAndId player) {
        return false;
    }

    @Override
    public String getMotd() {
        return "";
    }

    @Override
    public String getServerVersion() {
        return "";
    }

    @Override
    public int getPlayerCount() {
        return 0;
    }

    @Override
    public int getMaxPlayers() {
        return 0;
    }

    @Override
    public void sendSystemMessage(net.minecraft.network.chat.Component message) {}

    @Override
    public boolean acceptsSuccess() {
        return false;
    }

    @Override
    public boolean acceptsFailure() {
        return false;
    }

    @Override
    public void reportChunkLoadFailure(
            Throwable error,
            net.minecraft.world.level.chunk.storage.RegionStorageInfo storageInfo,
            net.minecraft.world.level.ChunkPos pos) {}

    @Override
    public void reportChunkSaveFailure(
            Throwable error,
            net.minecraft.world.level.chunk.storage.RegionStorageInfo storageInfo,
            net.minecraft.world.level.ChunkPos pos) {}
}
