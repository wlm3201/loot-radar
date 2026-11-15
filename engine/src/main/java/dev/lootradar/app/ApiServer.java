package dev.lootradar.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.lootradar.core.catalog.ItemCatalog;
import dev.lootradar.core.search.SearchHit;
import dev.lootradar.core.search.SearchProgress;
import dev.lootradar.core.search.SearchReport;
import dev.lootradar.core.search.SearchRequest;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import dev.lootradar.engine.search.SearchEngine;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 HTTP 服务（仅监听 127.0.0.1）：目录/版本查询、搜索会话、SSE 事件重放与静态前端。
 *
 * <p>每个搜索会话把全部事件缓存在内存里，支持断线续传（Last-Event-ID）与刷新页面后 全量重放；取消只置标志位，不强制中断正在运行的扫描线程。
 */
public final class ApiServer implements AutoCloseable {

    private static final Gson GSON =
            new com.google.gson.GsonBuilder()
                    .registerTypeAdapter(
                            dev.lootradar.core.search.LootMethod.class,
                            (com.google.gson.JsonSerializer<dev.lootradar.core.search.LootMethod>)
                                    (method, type, context) ->
                                            new com.google.gson.JsonPrimitive(method.id))
                    .create();

    private final HttpServer server;
    private final VanillaRuntime runtime;
    private final ItemCatalog catalog;
    private final GameVersion activeVersion;
    private final Map<GameVersion, ItemCatalog> catalogs;
    private final Map<GameVersion, dev.lootradar.core.version.VersionRegistry> versionRegistries;
    private final AtomicLong searchIds = new AtomicLong();
    private final Map<Long, SearchSession> sessions = new ConcurrentHashMap<>();
    private final Path webRoot;
    private final Map<GameVersion, Map<String, String>> tableOverrides;
    private final dev.lootradar.engine.search.ScanCache scanCache =
            new dev.lootradar.engine.search.ScanCache(60_000);

    public ApiServer(
            int port,
            VanillaRuntime runtime,
            Map<GameVersion, ItemCatalog> catalogs,
            Map<GameVersion, dev.lootradar.core.version.VersionRegistry> versionRegistries,
            Path webRoot)
            throws IOException {
        this(port, runtime, catalogs, versionRegistries, webRoot, Map.of());
    }

    public ApiServer(
            int port,
            VanillaRuntime runtime,
            Map<GameVersion, ItemCatalog> catalogs,
            Map<GameVersion, dev.lootradar.core.version.VersionRegistry> versionRegistries,
            Path webRoot,
            Map<GameVersion, Map<String, String>> tableOverrides)
            throws IOException {
        this.runtime = runtime;
        this.catalogs = catalogs;
        this.versionRegistries = versionRegistries;
        this.tableOverrides = tableOverrides;
        // 默认版本优先 26.2；发行包只带其它版本数据时退化为第一个已加载版本。
        this.activeVersion =
                catalogs.containsKey(GameVersion.V26_2)
                        ? GameVersion.V26_2
                        : catalogs.keySet().iterator().next();
        this.catalog = catalogs.get(activeVersion);
        this.webRoot = webRoot;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/api/catalog", this::handleCatalog);
        this.server.createContext("/api/versions", this::handleVersions);
        this.server.createContext("/api/shutdown", this::handleShutdown);
        this.server.createContext("/api/search", this::handleSearch);
        this.server.createContext("/", this::handleStatic);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleCatalog(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        ItemCatalog requested = catalog;
        GameVersion requestedVersion = activeVersion;
        String query = exchange.getRequestURI().getQuery();
        // 只接受键名恰好为 version 的参数，避免 ?notversion=... 之类误入解析分支。
        String versionParam = null;
        if (query != null) {
            for (String part : query.split("&")) {
                int separator = part.indexOf('=');
                if (separator > 0 && part.substring(0, separator).equals("version")) {
                    versionParam = part.substring(separator + 1);
                    break;
                }
            }
        }
        if (versionParam != null) {
            try {
                GameVersion parsed = GameVersion.parse(versionParam);
                ItemCatalog byVersion = catalogs.get(parsed);
                if (byVersion == null) {
                    sendStatus(exchange, 404);
                    return;
                }
                requested = byVersion;
                requestedVersion = parsed;
            } catch (IllegalArgumentException unknownVersion) {
                sendStatus(exchange, 400);
                return;
            }
        }
        JsonObject root = new JsonObject();
        root.add("groups", GSON.toJsonTree(requested.groups().values()));
        root.add("items", GSON.toJsonTree(requested.items().values()));
        var registry =
                versionRegistries.getOrDefault(
                        requestedVersion, versionRegistries.values().iterator().next());
        JsonObject structureNames = new JsonObject();
        registry.structures().forEach((id, spec) -> structureNames.addProperty(id, spec.zhName()));
        root.add("structures", structureNames);
        sendJson(exchange, 200, GSON.toJson(root));
    }

    private void handleVersions(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        // active 必须来自实际加载到的目录，否则只带单一版本的发行包会请求到 404。
        sendJson(
                exchange,
                200,
                GSON.toJson(
                        Map.of(
                                "versions",
                                catalogs.keySet().stream().map(v -> v.id).sorted().toList(),
                                "active",
                                activeVersion.id)));
    }

    private void handleShutdown(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, 405);
            return;
        }
        sendJson(exchange, 200, "{\"ok\":true}");
        Thread.startVirtualThread(
                () -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("shutdown requested from UI");
                    Runtime.getRuntime().halt(0);
                });
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length == 3 && "POST".equals(exchange.getRequestMethod())) {
            startSearch(exchange);
            return;
        }
        if (parts.length == 4
                && parts[3].equals("active")
                && "GET".equals(exchange.getRequestMethod())) {
            long last = searchIds.get();
            SearchSession session = sessions.get(last);
            if (session == null) {
                sendJson(exchange, 200, "{\"searchId\":0,\"finished\":true}");
            } else {
                sendJson(
                        exchange,
                        200,
                        GSON.toJson(
                                Map.of("searchId", session.id, "finished", session.isFinished())));
            }
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "search".equals(parts[2])) {
            long id;
            try {
                id = Long.parseLong(parts[3]);
            } catch (NumberFormatException badId) {
                sendJson(exchange, 400, "{\"error\":\"bad search id\"}");
                return;
            }
            SearchSession session = sessions.get(id);
            if (session == null) {
                sendStatus(exchange, 404);
                return;
            }
            if (parts[4].equals("events") && "GET".equals(exchange.getRequestMethod())) {
                streamEvents(exchange, session);
                return;
            }
            if (parts[4].equals("cancel") && "POST".equals(exchange.getRequestMethod())) {
                session.cancel();
                sendJson(exchange, 200, "{\"ok\":true}");
                return;
            }
        }
        sendStatus(exchange, 405);
    }

    private void startSearch(HttpExchange exchange) throws IOException {
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000;
        sessions.values().removeIf(session -> session.isFinished() && session.finishedAt < cutoff);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        SearchRequest request;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            request =
                    new SearchRequest(
                            json.get("seed").getAsLong(),
                            GameVersion.parse(json.get("version").getAsString()),
                            json.get("centerX").getAsInt(),
                            json.get("centerZ").getAsInt(),
                            json.get("radius").getAsInt(),
                            json.get("minCountPerItem").getAsInt(),
                            json.get("lootingLevel").getAsInt(),
                            json.get("startOffset").getAsInt(),
                            json.get("skipDungeon").getAsBoolean(),
                            GSON.fromJson(
                                    json.getAsJsonArray("itemIds"),
                                    new com.google.gson.reflect.TypeToken<
                                            java.util.List<String>>() {}.getType()),
                            json.has("itemBudgetSeconds")
                                    ? json.get("itemBudgetSeconds").getAsInt()
                                    : json.has("typeBudgetSeconds")
                                            ? json.get("typeBudgetSeconds").getAsInt()
                                            : 60,
                            json.has("structures")
                                    ? GSON.fromJson(
                                            json.getAsJsonArray("structures"),
                                            new com.google.gson.reflect.TypeToken<
                                                    java.util.List<String>>() {}.getType())
                                    : java.util.List.<String>of());
        } catch (RuntimeException badRequest) {
            sendJson(exchange, 400, "{\"error\":" + GSON.toJson(String.valueOf(badRequest)) + "}");
            return;
        }

        long id = searchIds.incrementAndGet();
        SearchSession session = new SearchSession(id, request);
        sessions.put(id, session);
        session.start();
        sendJson(exchange, 200, GSON.toJson(Map.of("searchId", id)));
    }

    private void streamEvents(HttpExchange exchange, SearchSession session) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        int fromIndex = 0;
        String lastEventId = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (lastEventId != null) {
            try {
                fromIndex = Integer.parseInt(lastEventId.trim()) + 1;
            } catch (NumberFormatException ignored) {
                fromIndex = 0;
            }
        }
        try (OutputStream output = exchange.getResponseBody()) {
            session.await(output, fromIndex);
        }
    }

    private final class SearchSession {
        private final long id;
        private final SearchRequest request;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<String> events = new ArrayList<>();
        private boolean finished;
        private volatile long finishedAt;

        SearchSession(long id, SearchRequest request) {
            this.id = id;
            this.request = request;
        }

        void start() {
            Thread.startVirtualThread(
                    () -> {
                        try (VanillaRuntime owned =
                                request.seed() == runtime.worldSeed()
                                        ? null
                                        : VanillaRuntime.load(request.seed())) {
                            VanillaRuntime searchRuntime = owned == null ? runtime : owned;
                            SearchEngine engine =
                                    new SearchEngine(
                                            searchRuntime,
                                            new VanillaLootOracle(
                                                    searchRuntime,
                                                    tableOverrides.getOrDefault(
                                                            request.version(), Map.of())),
                                            versionRegistries.get(request.version()),
                                            catalogs.get(request.version()),
                                            scanCache);
                            SearchReport report =
                                    engine.search(
                                            request,
                                            new SearchEngine.Listener() {
                                                @Override
                                                public void onHit(SearchHit hit) {
                                                    publish("hit", GSON.toJson(hit));
                                                }

                                                @Override
                                                public void onProgress(SearchProgress progress) {
                                                    publish("progress", GSON.toJson(progress));
                                                }
                                            },
                                            cancelled);
                            publish("done", GSON.toJson(report));
                        } catch (Throwable exception) {
                            java.io.StringWriter stack = new java.io.StringWriter();
                            exception.printStackTrace(new java.io.PrintWriter(stack));
                            System.err.println("search " + id + " failed:\n" + stack);
                            publish(
                                    "error",
                                    GSON.toJson(
                                            Map.of(
                                                    "message", String.valueOf(exception),
                                                    "stack", stack.toString())));
                        }
                    });
        }

        synchronized void publish(String type, String data) {
            String event = "id: " + events.size() + "\n" + sse(type, data);
            events.add(event);
            if (type.equals("done") || type.equals("error")) {
                finished = true;
                finishedAt = System.currentTimeMillis();
            }
            notifyAll();
        }

        synchronized boolean isFinished() {
            return finished;
        }

        void cancel() {
            cancelled.set(true);
        }

        void await(OutputStream output, int fromIndex) throws IOException {
            int index = fromIndex;
            while (true) {
                String event = null;
                boolean keepAlive = false;
                synchronized (this) {
                    while (index >= events.size() && !finished) {
                        try {
                            wait(15_000);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (index >= events.size() && !finished) {
                            keepAlive = true;
                            break;
                        }
                    }
                    if (!keepAlive && index < events.size()) {
                        event = events.get(index++);
                    }
                }
                if (keepAlive) {
                    output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    continue;
                }
                if (event == null) {
                    return;
                }
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (event.startsWith("event: done") || event.startsWith("event: error")) {
                    return;
                }
            }
        }
    }

    private static String sse(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // 未知 API 路径不能回退成 index.html，否则前端会把 HTML 当 JSON 解析。
        if (path.startsWith("/api/")) {
            sendStatus(exchange, 404);
            return;
        }
        String relative = path.equals("/") ? "index.html" : path.substring(1);
        Path file = webRoot.resolve(relative).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            file = webRoot.resolve("index.html");
        }
        if (!Files.isRegularFile(file)) {
            byte[] fallback =
                    "<html><body>loot-radar API is running. Build the web UI (web/) to serve it here.</body></html>"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, fallback.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(fallback);
            }
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        String contentType = contentType(file.getFileName().toString());
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
