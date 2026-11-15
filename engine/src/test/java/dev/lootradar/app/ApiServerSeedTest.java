package dev.lootradar.app;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.lootradar.core.catalog.ItemCatalog;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.VanillaRuntime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HTTP 冒烟：POST /api/search 提交非零 64 位种子后，SSE 必须正常结束且无 error 事件。 */
class ApiServerSeedTest {
    @Test
    void nonzero64BitSeedGetsItsOwnRuntimeThroughHttp() throws Exception {
        var data = VersionRegistry.resolveDataRoot();
        var version = GameVersion.V26_2;
        try (var runtime = VanillaRuntime.load(0L);
                var server =
                        new ApiServer(
                                0,
                                runtime,
                                Map.of(version, ItemCatalog.load(data, version.id)),
                                Map.of(version, VersionRegistry.load(version, data)),
                                Path.of("."));
                var client = HttpClient.newHttpClient()) {
            server.start();
            var uri = "http://127.0.0.1:" + server.port();
            var response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(uri + "/api/search"))
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    """
                            {"seed":"0","version":"26.2","centerX":0,"centerZ":0,
                             "radius":1,"minCountPerItem":1,"lootingLevel":0,"startOffset":1,
                             "skipDungeon":true,
                             "itemIds":["minecraft:music_disc_bounce"]}
                            """))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            long id =
                    JsonParser.parseString(response.body())
                            .getAsJsonObject()
                            .get("searchId")
                            .getAsLong();
            var events =
                    client.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            uri + "/api/search/" + id + "/events"))
                                            .timeout(Duration.ofSeconds(60))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body();
            assertTrue(events.contains("event: done"), events);
            assertFalse(events.contains("event: error"), events);
        }
    }
}
