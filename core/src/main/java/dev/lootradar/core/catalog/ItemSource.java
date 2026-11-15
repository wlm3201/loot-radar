package dev.lootradar.core.catalog;

import java.util.List;

public record ItemSource(
        String label, List<String> structures, String method, List<String> pieces, String skipKey) {
    public ItemSource {
        structures = List.copyOf(structures);
        pieces = pieces == null ? List.of() : List.copyOf(pieces);
    }
}
