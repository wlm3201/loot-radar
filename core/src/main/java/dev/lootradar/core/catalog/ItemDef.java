package dev.lootradar.core.catalog;

import java.util.List;

public record ItemDef(String id, String zhName, String group, List<ItemSource> sources) {
    public ItemDef {
        sources = List.copyOf(sources);
    }
}
