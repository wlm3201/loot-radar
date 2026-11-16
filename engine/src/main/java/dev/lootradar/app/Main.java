package dev.lootradar.app;

import dev.lootradar.core.catalog.ItemCatalog;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.VanillaRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 应用入口：加载各版本数据目录 → 启动原生运行时 → 起 HTTP 服务并自动打开浏览器。 */
public final class Main {

    public static void main(String[] args) throws Exception {
        java.io.PrintStream console = System.out;
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8765;

        String existing = findRunningInstance(port);
        if (existing != null) {
            console.println("loot-radar already running at " + existing);
            openBrowser(console, existing);
            return;
        }

        Path appHome = appHome();
        Path dataRoot = resolveDataRoot(appHome);
        Path webRoot = resolveWebRoot(appHome);

        Map<GameVersion, ItemCatalog> catalogs = new LinkedHashMap<>();
        Map<GameVersion, VersionRegistry> versionRegistries = new LinkedHashMap<>();
        for (GameVersion version : GameVersion.values()) {
            if (Files.isDirectory(dataRoot.resolve(version.id))
                    && Files.isRegularFile(dataRoot.resolve(version.id).resolve("items.json"))) {
                versionRegistries.put(version, VersionRegistry.load(version, dataRoot));
                catalogs.put(version, ItemCatalog.load(dataRoot, version.id));
            }
        }
        if (catalogs.isEmpty()) {
            throw new IllegalStateException(
                    "no version data found under " + dataRoot.toAbsolutePath());
        }
        for (var entry : catalogs.entrySet()) {
            var structures = versionRegistries.get(entry.getKey()).structures();
            for (var item : entry.getValue().items().values()) {
                for (var source : item.sources()) {
                    for (String structureId : source.structures()) {
                        if (!structures.containsKey(structureId)) {
                            throw new IllegalStateException(
                                    "items.json of "
                                            + entry.getKey().id
                                            + " references unknown structure "
                                            + structureId);
                        }
                    }
                }
            }
        }

        Map<GameVersion, Map<String, String>> tableOverrides = new LinkedHashMap<>();
        for (GameVersion version : GameVersion.values()) {
            Path dir = dataRoot.resolve(version.id).resolve("loot_tables");
            Map<String, String> map = new LinkedHashMap<>();
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    for (Path path : walk.filter(p -> p.toString().endsWith(".json")).toList()) {
                        String rel = dir.relativize(path).toString().replace('\\', '/');
                        map.put(
                                "minecraft:" + rel.substring(0, rel.length() - 5),
                                Files.readString(path));
                    }
                }
            }
            tableOverrides.put(version, map);
        }

        console.println("Loading vanilla worldgen for " + GameVersion.V26_2.id + " ...");
        VanillaRuntime runtime = VanillaRuntime.load(0L);
        ApiServer server = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                server =
                        new ApiServer(
                                port + attempt,
                                runtime,
                                catalogs,
                                versionRegistries,
                                webRoot,
                                tableOverrides);
                server.start();
                break;
            } catch (java.io.IOException taken) {
                console.println("port " + (port + attempt) + " busy, trying next...");
            }
        }
        if (server == null) {
            throw new IllegalStateException("no free port in " + port + ".." + (port + 9));
        }
        String url = "http://127.0.0.1:" + server.port();
        console.println("loot-radar ready: " + url);
        console.println("Press Ctrl+C to stop.");
        openBrowser(console, url);
        Thread.currentThread().join();
    }

    private static String findRunningInstance(int port) {
        for (int candidate = port; candidate <= port + 9; candidate++) {
            try {
                var connection =
                        (java.net.HttpURLConnection)
                                java.net
                                        .URI
                                        .create("http://127.0.0.1:" + candidate + "/api/versions")
                                        .toURL()
                                        .openConnection();
                connection.setConnectTimeout(250);
                connection.setReadTimeout(250);
                if (connection.getResponseCode() == 200) {
                    connection.disconnect();
                    return "http://127.0.0.1:" + candidate;
                }
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void openBrowser(java.io.PrintStream console, String url) {
        if (Boolean.getBoolean("lootradar.noBrowser")
                || System.getenv("LOOTRADAR_NO_BROWSER") != null) {
            console.println("browser auto-open disabled (" + url + ")");
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
        } catch (Exception exception) {
            console.println("could not open browser automatically: " + exception.getMessage());
        }
    }

    private static Path appHome() {
        try {
            Path codeSource =
                    Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path home = Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
            if (home != null) {
                return home;
            }
        } catch (Exception ignored) {
        }
        return Path.of("");
    }

    private static Path findUp(Path start, String first, String... more) {
        Path dir = start.toAbsolutePath().normalize();
        for (int level = 0; level < 4 && dir != null; level++) {
            Path candidate = dir.resolve(first);
            for (String part : more) {
                candidate = candidate.resolve(part);
            }
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Path resolveWebRoot(Path appHome) {
        for (Path start : new Path[] {Path.of("."), appHome}) {
            Path candidate = findUp(start, "web", "assets");
            if (candidate != null) {
                candidate = candidate.getParent();
            } else {
                candidate = findUp(start, "web", "dist");
            }
            if (candidate != null) {
                return candidate;
            }
        }
        return Path.of("web", "dist").toAbsolutePath().normalize();
    }

    private static Path resolveDataRoot(Path appHome) {
        for (Path start : new Path[] {Path.of("."), appHome}) {
            Path candidate = findUp(start, "data");
            if (candidate != null) {
                return candidate;
            }
        }
        return Path.of("data").toAbsolutePath().normalize();
    }
}
