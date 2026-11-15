package dev.lootradar.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** cubiomes 黄金向量：丛林神殿 4 个箱子的 (战利品表, lootSeed) 集合必须精确相等。 */
class JunglePyramidGoldenTest {

    private static final long SEED = -4911581849163216631L;
    private static final Set<String> EXPECTED =
            Set.of(
                    "chests/jungle_temple_dispenser#230780238426740339",
                    "chests/jungle_temple_dispenser#318519966113226178",
                    "chests/jungle_temple#-894605277458083196",
                    "chests/jungle_temple#3293249825004981781");

    @Test
    void junglePyramidChestSeedsMatchGoldenVector() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if (!net.minecraft.SharedConstants.getCurrentVersion().id().startsWith("26.2")) {
            return;
        }
        try (VanillaRuntime runtime = VanillaRuntime.load(SEED)) {
            var version = GameVersion.parse(SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var set = registry.sets().get("minecraft:jungle_temples");
            var members =
                    set.structures().stream()
                            .map(m -> new VanillaRuntime.StructureSetMember(m.id(), m.weight()))
                            .toList();
            var start =
                    runtime.generateSelectedStructure(
                            members, "minecraft:overworld", new ChunkPos(16, -126));
            assertTrue(
                    MinecraftVersionAdapter.isValidStart(start),
                    "golden vector drifted: jungle pyramid start (16,-126)");
            String id =
                    runtime.registries()
                            .lookupOrThrow(Registries.STRUCTURE)
                            .getKey(start.getStructure())
                            .toString();
            assertEquals("minecraft:jungle_pyramid", id);
            List<ContainerRecord> records =
                    StructureScanner.scan(runtime, id, "minecraft:overworld", start);
            Set<String> actual =
                    records.stream()
                            .map(
                                    record ->
                                            record.lootTable().replace("minecraft:", "")
                                                    + "#"
                                                    + record.lootSeed())
                            .collect(Collectors.toSet());
            assertEquals(
                    EXPECTED,
                    actual,
                    "jungle pyramid loot seeds must match the cubiomes golden vector");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
