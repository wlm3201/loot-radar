package com.lootradar.dump;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;

/**
 * Bounce 唱片真值：枚举矿井结构起点、生成其覆盖区块，并监听箱子矿车实体加载事件，
 * 写出 {@code loot-radar-bounce-truth/1}；版本无该唱片时直接标记 unavailable。
 */
final class BounceCoverageDump {
    private static final Identifier DISC = Identifier.parse("minecraft:music_disc_bounce");
    private static final Identifier SULFUR = Identifier.parse("minecraft:sulfur_caves");
    private static BounceCoverageDump active;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final int radius = Integer.getInteger("lootradar.coverageRadius", 5000);
    private final int centerX = Integer.getInteger("lootradar.coverageX", 0);
    private final int centerZ = Integer.getInteger("lootradar.coverageZ", 0);
    private final Path output = Path.of(System.getProperty("lootradar.coverageOut", "truth.json"));
    private final long started = System.nanoTime();
    private final List<ChunkPos> candidates = new ArrayList<>();
    private final Set<Long> targetKeys = new HashSet<>();
    private List<ChunkPos> targets = List.of();
    private final Set<UUID> seen = new HashSet<>();
    private final List<Map<String, Object>> carts = new ArrayList<>();
    private int candidateIndex, validStarts, chunkIndex, settleTicks;
    private long footprintCells;
    private long lastProgress;
    private String phase = "structure_starts";

    static void register() {
        if (System.getProperty("lootradar.coverageRadius") == null) return;
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> {
                    try {
                        active = new BounceCoverageDump(server);
                    } catch (Throwable error) {
                        fail(server, error);
                    }
                });
        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    if (active == null) return;
                    try {
                        active.tick();
                    } catch (Throwable error) {
                        fail(server, error);
                    }
                });
        ServerEntityEvents.ENTITY_LOAD.register(
                (entity, level) -> {
                    if (active != null
                            && level == active.level
                            && entity instanceof MinecartChest cart) {
                        try {
                            active.capture(cart);
                        } catch (Throwable error) {
                            fail(level.getServer(), error);
                        }
                    }
                });
    }

    private static void fail(MinecraftServer server, Throwable error) {
        LootRadarDump.LOGGER.error("BOUNCE_COVERAGE_FAILED", error);
        if (active != null) {
            active.phase = "failed";
            try {
                active.write(false, error.toString());
            } catch (Exception ignored) {
            }
        }
        active = null;
        server.execute(() -> server.halt(false));
    }

    private BounceCoverageDump(MinecraftServer server) {
        this.server = server;
        this.level = server.overworld();
        if (!BuiltInRegistries.ITEM.containsKey(DISC)) {
            phase = "unavailable_in_version";
            return;
        }
        var set =
                level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE_SET)
                        .getValueOrThrow(
                                ResourceKey.create(
                                        Registries.STRUCTURE_SET,
                                        Identifier.parse("minecraft:mineshafts")));
        var state = level.getChunkSource().getGeneratorState();
        int extent = radius + 192;
        for (int x = (centerX - extent) >> 4; x <= (centerX + extent) >> 4; x++) {
            for (int z = (centerZ - extent) >> 4; z <= (centerZ + extent) >> 4; z++) {
                if (set.placement().isStructureChunk(state, x, z))
                    candidates.add(new ChunkPos(x, z));
            }
        }
        progress();
    }

    private boolean inCircle(int x, int z) {
        long dx = (long) x - centerX, dz = (long) z - centerZ;
        return dx * dx + dz * dz <= (long) radius * radius;
    }

    private void tick() throws Exception {
        if (phase.equals("unavailable_in_version")) {
            finish();
            return;
        }
        if (phase.equals("structure_starts")) {
            for (int n = 0; n < 8 && candidateIndex < candidates.size(); n++) {
                ChunkPos pos = candidates.get(candidateIndex++);
                var chunk = level.getChunk(pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS, true);
                for (var entry : chunk.getAllStarts().entrySet()) {
                    String id =
                            level.registryAccess()
                                    .lookupOrThrow(Registries.STRUCTURE)
                                    .getKey(entry.getKey())
                                    .toString();
                    if (!id.equals("minecraft:mineshaft") && !id.equals("minecraft:mineshaft_mesa"))
                        continue;
                    var start = entry.getValue();
                    if (!VersionCompat.isValidStart(start)) continue;
                    validStarts++;
                    for (var piece : start.getPieces()) {
                        if (!(piece instanceof MineshaftPieces.MineShaftCorridor)) continue;
                        var box = piece.getBoundingBox();
                        for (int x = box.minX(); x <= box.maxX(); x++) {
                            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                                if (!inCircle(x, z)) continue;
                                footprintCells++;
                                if (level.getBiome(new BlockPos(x, box.minY(), z)).is(SULFUR)) {
                                    targetKeys.add(ChunkPos.pack(x >> 4, z >> 4));
                                }
                            }
                        }
                    }
                }
            }
            if (candidateIndex == candidates.size()) {
                targets =
                        targetKeys.stream()
                                .map(ChunkPos::unpack)
                                .sorted(
                                        Comparator.comparingInt(ChunkPos::x)
                                                .thenComparingInt(ChunkPos::z))
                                .toList();
                phase = "full_chunks";
                progress();
            }
        } else if (phase.equals("full_chunks")) {
            for (int n = 0; n < 4 && chunkIndex < targets.size(); n++) {
                ChunkPos pos = targets.get(chunkIndex++);
                level.getChunk(
                        pos.x(),
                        pos.z());
            }
            for (var cart :
                    level.getEntities(
                            net.minecraft.world.level.entity.EntityTypeTest.forClass(
                                    MinecartChest.class),
                            cart -> true)) capture(cart);
            if (chunkIndex == targets.size() && ++settleTicks >= 20) finish();
        }
        if (System.nanoTime() - lastProgress > 10_000_000_000L) progress();
    }

    private void capture(MinecartChest cart) {
        if (!seen.add(cart.getUUID())) return;
        BlockPos pos = cart.blockPosition();
        if (!inCircle(pos.getX(), pos.getZ())) return;
        var table = cart.getContainerLootTable();
        if (table == null
                || !table.identifier().toString().equals("minecraft:chests/abandoned_mineshaft"))
            return;
        long seed = cart.getContainerLootTableSeed();
        String biome = level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
        Map<String, Integer> items = new TreeMap<>();
        for (int slot = 0; slot < cart.getContainerSize(); slot++) {
            var item = cart.getItem(slot);
            if (!item.isEmpty())
                items.merge(
                        BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                        item.getCount(),
                        Integer::sum);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pos", List.of(pos.getX(), pos.getY(), pos.getZ()));
        row.put("origin", List.of(cart.getX(), cart.getY(), cart.getZ()));
        row.put("uuid", cart.getStringUUID());
        row.put("lootSeed", Long.toString(seed));
        row.put("biome", biome);
        row.put("bounceCount", items.getOrDefault(DISC.toString(), 0));
        row.put("items", items);
        carts.add(row);
    }

    private void progress() {
        lastProgress = System.nanoTime();
        LootRadarDump.LOGGER.info(
                "BOUNCE_COVERAGE {} starts {}/{} valid {} chunks {}/{} carts {}",
                phase,
                candidateIndex,
                candidates.size(),
                validStarts,
                chunkIndex,
                targets.size(),
                carts.size());
        try {
            write(false, "");
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void finish() throws Exception {
        if (!phase.equals("unavailable_in_version")) {
            for (var row : carts) {
                if (((Number) row.get("bounceCount")).intValue() <= 0) continue;
                @SuppressWarnings("unchecked")
                var pos = (List<Integer>) row.get("pos");
                if (!targetKeys.contains(ChunkPos.pack(pos.get(0) >> 4, pos.get(2) >> 4)))
                    throw new IllegalStateException("Actual Bounce outside census gate: " + row);
            }
            phase = "complete";
        }
        write(true, "");
        LootRadarDump.LOGGER.info("BOUNCE_COVERAGE_COMPLETE {}", output.toAbsolutePath());
        active = null;
        server.execute(() -> server.halt(false));
    }

    private void write(boolean complete, String error) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "loot-radar-bounce-truth/1");
        root.put("version", SharedConstants.getCurrentVersion().id());
        root.put("seed", Long.toString(level.getSeed()));
        root.put("center", List.of(centerX, centerZ));
        root.put("radius", radius);
        root.put("complete", complete);
        root.put("phase", phase);
        root.put("error", error);
        root.put("elapsedSeconds", (System.nanoTime() - started) / 1e9);
        root.put("candidateStarts", candidates.size());
        root.put("startsChecked", candidateIndex);
        root.put("validStarts", validStarts);
        root.put("footprintCellsChecked", footprintCells);
        root.put("targetChunks", targets.size());
        root.put("fullChunksGenerated", chunkIndex);
        root.put("observedCarts", carts);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path temp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        Files.move(temp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
