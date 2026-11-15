package dev.lootradar.core.version;

import java.util.Arrays;

public enum GameVersion {
    V26_1_2("26.1.2"),
    V26_2("26.2"),
    V26_3("26.3"),
    V26_4("26.4");

    public final String id;

    GameVersion(String id) {
        this.id = id;
    }

    public static GameVersion parse(String text) {
        String normalized = text == null ? "" : text.trim().split("-", 2)[0];
        return Arrays.stream(values())
                .filter(v -> v.id.equals(normalized))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "unsupported game version: "
                                                + text
                                                + " (expected one of 26.1.2, 26.2, 26.3, 26.4)"));
    }

    @Override
    public String toString() {
        return id;
    }
}
