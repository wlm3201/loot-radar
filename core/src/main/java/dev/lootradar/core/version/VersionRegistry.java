package dev.lootradar.core.version;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lootradar.core.structure.PlacementSpec;
import dev.lootradar.core.structure.StructureSetSpec;
import dev.lootradar.core.structure.StructureSpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本目录的加载与一致性校验：{@code data/<version>/structures.json} 提供结构集/结构， {@code items.json}
 * 提供物品来源。这里只做静态校验，真正的世界生成仍由原生运行时完成。
 */
public final class VersionRegistry {

    private static final Gson GSON = new Gson();
    private static final Set<String> DIMENSIONS = Set.of("overworld", "the_nether", "the_end");
    private static final Set<String> SKIP_KEYS = Set.of("dungeon", "stronghold_corridor");

    private final GameVersion version;
    private final Map<String, StructureSetSpec> sets;
    private final Map<String, StructureSpec> structures;

    private VersionRegistry(
            GameVersion version,
            Map<String, StructureSetSpec> sets,
            Map<String, StructureSpec> structures) {
        this.version = version;
        this.sets = sets;
        this.structures = structures;
    }

    public GameVersion version() {
        return version;
    }

    public Map<String, StructureSetSpec> sets() {
        return Collections.unmodifiableMap(sets);
    }

    public Map<String, StructureSpec> structures() {
        return Collections.unmodifiableMap(structures);
    }

    public StructureSpec structure(String id) {
        StructureSpec spec = structures.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("unknown structure for " + version.id + ": " + id);
        }
        return spec;
    }

    public static VersionRegistry load(GameVersion version, Path dataRoot) {
        Path dir = dataRoot.resolve(version.id);
        if (!Files.isDirectory(dir)) {
            throw new DataFormatException("missing data directory: " + dir.toAbsolutePath());
        }
        try (Reader reader = Files.newBufferedReader(dir.resolve("structures.json"))) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                throw new DataFormatException("empty structures.json in " + dir.toAbsolutePath());
            }
            return parse(version, root);
        } catch (IOException e) {
            throw new DataFormatException(
                    "failed to read structures.json from " + dir.toAbsolutePath(), e);
        }
    }

    static VersionRegistry parse(GameVersion version, JsonObject root) {
        String declaredVersion = root.has("version") ? root.get("version").getAsString() : null;
        if (!version.id.equals(declaredVersion)) {
            throw new DataFormatException(
                    "structures.json version mismatch: expected "
                            + version.id
                            + " but file declares "
                            + declaredVersion);
        }

        Map<String, StructureSetSpec> sets = new LinkedHashMap<>();
        for (var element : root.getAsJsonArray("structureSets")) {
            JsonObject obj = element.getAsJsonObject();
            StructureSetSpec set = parseSet(obj);
            if (sets.put(set.id(), set) != null) {
                throw new DataFormatException("duplicate structure set id: " + set.id());
            }
        }

        Map<String, StructureSpec> structures = new LinkedHashMap<>();
        for (var element : root.getAsJsonArray("structures")) {
            JsonObject obj = element.getAsJsonObject();
            StructureSpec spec = parseStructure(obj);
            if (structures.put(spec.id(), spec) != null) {
                throw new DataFormatException("duplicate structure id: " + spec.id());
            }
        }

        validate(sets, structures);
        return new VersionRegistry(
                version,
                Collections.unmodifiableMap(sets),
                Collections.unmodifiableMap(structures));
    }

    private static StructureSetSpec parseSet(JsonObject obj) {
        String id = obj.get("id").getAsString();
        JsonObject placement = obj.getAsJsonObject("placement");
        PlacementSpec.Type type = PlacementSpec.Type.parse(placement.get("type").getAsString());

        PlacementSpec spec =
                new PlacementSpec(
                        type,
                        placement.has("spreadType")
                                ? placement.get("spreadType").getAsString()
                                : "linear",
                        placement.has("salt") ? placement.get("salt").getAsInt() : 0,
                        placement.has("spacing") ? placement.get("spacing").getAsInt() : 0,
                        placement.has("separation") ? placement.get("separation").getAsInt() : 0,
                        placement.has("frequency") ? placement.get("frequency").getAsFloat() : 1.0f,
                        placement.has("frequencyReductionMethod")
                                ? placement.get("frequencyReductionMethod").getAsString()
                                : "none",
                        parseOffset(placement),
                        parseExclusion(placement),
                        placement.has("count") ? placement.get("count").getAsInt() : null,
                        placement.has("distance") ? placement.get("distance").getAsInt() : null,
                        placement.has("spread") ? placement.get("spread").getAsInt() : null);

        List<StructureSetSpec.Member> members = new ArrayList<>();
        for (var memberEl : obj.getAsJsonArray("structures")) {
            JsonObject member = memberEl.getAsJsonObject();
            members.add(
                    new StructureSetSpec.Member(
                            member.get("id").getAsString(),
                            member.has("weight") ? member.get("weight").getAsInt() : 1));
        }
        return new StructureSetSpec(id, spec, List.copyOf(members));
    }

    private static int[] parseOffset(JsonObject placement) {
        if (!placement.has("locateOffset")) {
            return null;
        }
        var arr = placement.getAsJsonArray("locateOffset");
        return new int[] {arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
    }

    private static PlacementSpec.ExclusionZone parseExclusion(JsonObject placement) {
        if (!placement.has("exclusionZone")) {
            return null;
        }
        JsonObject zone = placement.getAsJsonObject("exclusionZone");
        return new PlacementSpec.ExclusionZone(
                zone.get("chunkCount").getAsInt(), zone.get("otherSet").getAsString());
    }

    private static StructureSpec parseStructure(JsonObject obj) {
        return new StructureSpec(
                obj.get("id").getAsString(),
                obj.get("zhName").getAsString(),
                obj.get("dimension").getAsString(),
                obj.has("set") ? obj.get("set").getAsString() : null,
                obj.has("skipKey") ? obj.get("skipKey").getAsString() : null,
                obj.has("notes") ? obj.get("notes").getAsString() : null,
                obj.has("decorationIndex") ? obj.get("decorationIndex").getAsInt() : null,
                obj.has("generationStep") ? obj.get("generationStep").getAsInt() : null,
                obj.has("featureIndex") ? obj.get("featureIndex").getAsInt() : null,
                obj.has("featureStep") ? obj.get("featureStep").getAsInt() : null,
                obj.has("featureIndexDeep") ? obj.get("featureIndexDeep").getAsInt() : null,
                obj.has("featureStepDeep") ? obj.get("featureStepDeep").getAsInt() : null,
                obj.has("rarity") ? obj.get("rarity").getAsInt() : null);
    }

    private static void validate(
            Map<String, StructureSetSpec> sets, Map<String, StructureSpec> structures) {
        for (StructureSetSpec set : sets.values()) {
            PlacementSpec p = set.placement();
            if (p.type() == PlacementSpec.Type.RANDOM_SPREAD && p.spacing() <= p.separation()) {
                throw new DataFormatException(
                        "set " + set.id() + ": spacing must exceed separation");
            }
            if (p.type() == PlacementSpec.Type.CONCENTRIC_RINGS
                    && (p.count() == null || p.distance() == null || p.spread() == null)) {
                throw new DataFormatException(
                        "set " + set.id() + ": concentric_rings needs count/distance/spread");
            }
            if (set.structures().isEmpty()) {
                throw new DataFormatException("set " + set.id() + ": no member structures");
            }
            int totalWeight = 0;
            for (StructureSetSpec.Member member : set.structures()) {
                if (member.weight() < 1) {
                    throw new DataFormatException(
                            "set " + set.id() + ": weight must be >= 1 for " + member.id());
                }
                totalWeight += member.weight();
            }
            if (totalWeight <= 0) {
                throw new DataFormatException(
                        "set " + set.id() + ": total weight must be positive");
            }
        }

        Set<String> memberIds = new HashSet<>();
        for (StructureSetSpec set : sets.values()) {
            for (StructureSetSpec.Member member : set.structures()) {
                if (!memberIds.add(member.id())) {
                    throw new DataFormatException(
                            "structure " + member.id() + " appears in multiple sets");
                }
            }
        }

        for (StructureSpec structure : structures.values()) {
            if (!DIMENSIONS.contains(structure.dimension())) {
                throw new DataFormatException(
                        "structure " + structure.id() + ": bad dimension " + structure.dimension());
            }
            if (structure.skipKey() != null && !SKIP_KEYS.contains(structure.skipKey())) {
                throw new DataFormatException(
                        "structure " + structure.id() + ": unknown skipKey " + structure.skipKey());
            }
            if (structure.set() == null) {
                throw new DataFormatException("structure " + structure.id() + ": missing set");
            }
            StructureSetSpec set = sets.get(structure.set());
            if (set == null) {
                throw new DataFormatException(
                        "structure " + structure.id() + ": unknown set " + structure.set());
            }
            boolean member = set.structures().stream().anyMatch(m -> m.id().equals(structure.id()));
            if (!member) {
                throw new DataFormatException(
                        "structure "
                                + structure.id()
                                + " is not a member of declared set "
                                + structure.set());
            }
        }
    }

    public static Path resolveDataRoot() {
        String prop = System.getProperty("lootradar.data");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        Path direct = Path.of("data");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Path.of("..", "data");
        if (Files.isDirectory(parent)) {
            return parent;
        }
        throw new DataFormatException(
                "cannot find data root; run from repo root or set -Dlootradar.data");
    }
}
