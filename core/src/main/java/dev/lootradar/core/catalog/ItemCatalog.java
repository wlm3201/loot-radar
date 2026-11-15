package dev.lootradar.core.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.lootradar.core.version.DataFormatException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物品目录：{@code data/<version>/items.json} 的强类型视图。
 *
 * <p>每个 source 的 method 决定引擎走哪条预测路径（箱子/考古/陶罐/宝库/生物）， structures 是允许的结构白名单，pieces 用于限定 jigsaw
 * 片段，skipKey 用于跳过 刷怪房等难以精确复现的来源。
 */
public final class ItemCatalog {

    private static final Gson GSON = new Gson();
    private static final Set<String> METHODS =
            Set.of(
                    "chest",
                    "archaeology",
                    "pot_sherd",
                    "corridor_pot",
                    "vault",
                    "vault_ominous",
                    "entity");

    public record Group(String id, String zhName, boolean defaultSelected) {}

    private final Map<String, Group> groups;
    private final Map<String, ItemDef> items;

    private ItemCatalog(Map<String, Group> groups, Map<String, ItemDef> items) {
        this.groups = groups;
        this.items = items;
    }

    public Map<String, Group> groups() {
        return Collections.unmodifiableMap(groups);
    }

    public Map<String, ItemDef> items() {
        return Collections.unmodifiableMap(items);
    }

    public ItemDef item(String id) {
        ItemDef def = items.get(id);
        if (def == null) {
            throw new IllegalArgumentException("unknown item: " + id);
        }
        return def;
    }

    public List<ItemDef> itemsInGroup(String groupId) {
        return items.values().stream().filter(item -> item.group().equals(groupId)).toList();
    }

    public static ItemCatalog load(Path dataRoot, String versionId) {
        Path file = dataRoot.resolve(versionId).resolve("items.json");
        if (!Files.isRegularFile(file)) {
            throw new DataFormatException("missing items.json: " + file.toAbsolutePath());
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            String declared = root.has("version") ? root.get("version").getAsString() : null;
            if (!versionId.equals(declared)) {
                throw new DataFormatException(
                        "items.json version mismatch: expected "
                                + versionId
                                + " but file declares "
                                + declared);
            }
            return parse(root);
        } catch (IOException e) {
            throw new DataFormatException("failed to read " + file.toAbsolutePath(), e);
        }
    }

    static ItemCatalog parse(JsonObject root) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (var element : root.getAsJsonArray("groups")) {
            JsonObject obj = element.getAsJsonObject();
            Group group =
                    new Group(
                            obj.get("id").getAsString(),
                            obj.get("zhName").getAsString(),
                            obj.has("defaultSelected")
                                    && obj.get("defaultSelected").getAsBoolean());
            if (groups.put(group.id(), group) != null) {
                throw new DataFormatException("duplicate group: " + group.id());
            }
        }

        Map<String, ItemDef> items = new LinkedHashMap<>();
        for (var element : root.getAsJsonArray("items")) {
            JsonObject obj = element.getAsJsonObject();
            String id = obj.get("id").getAsString();
            String group = obj.get("group").getAsString();
            if (!groups.containsKey(group)) {
                throw new DataFormatException("item " + id + " references unknown group " + group);
            }
            List<ItemSource> sources = new ArrayList<>();
            for (var sourceEl : obj.getAsJsonArray("sources")) {
                JsonObject source = sourceEl.getAsJsonObject();
                List<String> structures = new ArrayList<>();
                for (var structureEl : source.getAsJsonArray("structures")) {
                    structures.add(structureEl.getAsString());
                }
                if (structures.isEmpty()) {
                    throw new DataFormatException(
                            "item " + id + " has a source without structures");
                }
                String method = source.get("method").getAsString();
                if (!METHODS.contains(method)) {
                    throw new DataFormatException("item " + id + " has unknown method " + method);
                }
                List<String> pieces = new ArrayList<>();
                if (source.has("pieces")) {
                    for (var pieceEl : source.getAsJsonArray("pieces")) {
                        pieces.add(pieceEl.getAsString());
                    }
                }
                sources.add(
                        new ItemSource(
                                source.get("label").getAsString(),
                                structures,
                                method,
                                pieces,
                                source.has("skipKey")
                                        ? source.get("skipKey").getAsString()
                                        : null));
            }
            if (sources.isEmpty()) {
                throw new DataFormatException("item " + id + " has no sources");
            }
            if (items.put(id, new ItemDef(id, obj.get("zhName").getAsString(), group, sources))
                    != null) {
                throw new DataFormatException("duplicate item: " + id);
            }
        }
        return new ItemCatalog(
                Collections.unmodifiableMap(groups), Collections.unmodifiableMap(items));
    }
}
