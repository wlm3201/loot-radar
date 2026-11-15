package dev.lootradar.engine;

abstract class VersionedMinecraftServer extends net.minecraft.server.MinecraftServer {
    protected VersionedMinecraftServer() {
        super(
                null,
                null,
                null,
                null,
                java.util.Optional.empty(),
                null,
                null,
                null,
                null,
                false,
                null);
    }
}
