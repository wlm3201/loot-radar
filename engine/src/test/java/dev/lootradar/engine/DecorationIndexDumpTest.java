package dev.lootradar.engine;

import dev.lootradar.core.version.GameVersion;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

/** 校准数据生成器（无断言）：导出各版本装饰阶段下标供 pin-decoration-indices.py 写回。 */
class DecorationIndexDumpTest {

    @Test
    void dumpDecorationIndices() {
        SharedConstants.tryDetectVersion();
        String versionId = GameVersion.parse(SharedConstants.getCurrentVersion().id()).id;
        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            var registry = runtime.registries().lookupOrThrow(Registries.STRUCTURE);
            TreeMap<String, int[]> rows = new TreeMap<>();
            for (var entry : registry.entrySet()) {
                String id = entry.getKey().identifier().toString();
                var coords = runtime.structureDecorationCoordinates(id);
                rows.put(id, new int[] {coords.step(), coords.indexWithinStep()});
            }
            var data =
                    dev.lootradar.core.version.VersionRegistry.load(
                            GameVersion.parse(versionId),
                            dev.lootradar.core.version.VersionRegistry.resolveDataRoot());
            for (var spec : data.structures().values()) {
                if (spec.set() != null && !spec.set().startsWith("lootradar:")) {
                    continue;
                }
                String dimensionId = "minecraft:" + spec.dimension();
                var coords = runtime.featureDecorationCoordinates(spec.id(), dimensionId);
                rows.put(
                        "feature:" + spec.id() + "@" + spec.dimension(),
                        new int[] {coords.step(), coords.indexWithinStep()});
                if (spec.id().equals("minecraft:monster_room")) {
                    var deep =
                            runtime.featureDecorationCoordinates(
                                    "minecraft:monster_room_deep", dimensionId);
                    rows.put(
                            "feature:minecraft:monster_room_deep@" + spec.dimension(),
                            new int[] {deep.step(), deep.indexWithinStep()});
                }
            }
            StringBuilder json = new StringBuilder("{\n");
            for (var row : rows.entrySet()) {
                json.append("  \"")
                        .append(row.getKey())
                        .append("\": [")
                        .append(row.getValue()[0])
                        .append(", ")
                        .append(row.getValue()[1])
                        .append("],\n");
            }
            if (json.length() > 2) {
                json.setLength(json.length() - 2);
                json.append('\n');
            }
            json.append("}\n");
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("build/decoration-index-" + versionId + ".json"),
                    json.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
