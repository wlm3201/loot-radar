package dev.lootradar.engine.search;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lootradar.core.catalog.ItemCatalog;
import dev.lootradar.core.search.SearchHit;
import dev.lootradar.core.search.SearchProgress;
import dev.lootradar.core.search.SearchReport;
import dev.lootradar.core.search.SearchRequest;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearchEngineTest {

    private static VanillaRuntime runtime;
    private static SearchEngine engine;

    @BeforeAll
    static void setUp() {
        runtime = VanillaRuntime.load(0L);
        VersionRegistry versions =
                VersionRegistry.load(GameVersion.V26_2, VersionRegistry.resolveDataRoot());
        ItemCatalog catalog =
                ItemCatalog.load(VersionRegistry.resolveDataRoot(), GameVersion.V26_2.id);
        engine = new SearchEngine(runtime, new VanillaLootOracle(runtime), versions, catalog);
    }

    @AfterAll
    static void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void rejectsMismatchedWorldSeedButNoLongerGatesBounceVersion() {
        var listener =
                new SearchEngine.Listener() {
                    public void onHit(SearchHit hit) {}

                    public void onProgress(SearchProgress progress) {}
                };
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        engine.search(
                                SearchRequest.defaults(
                                        123L,
                                        GameVersion.V26_2,
                                        List.of("minecraft:music_disc_bounce")),
                                listener,
                                new AtomicBoolean(false)));
        assertDoesNotThrow(
                () ->
                        engine.search(
                                new SearchRequest(
                                        0L,
                                        GameVersion.V26_3,
                                        0,
                                        0,
                                        1,
                                        1,
                                        0,
                                        1,
                                        true,
                                        List.of("minecraft:music_disc_bounce")),
                                listener,
                                new AtomicBoolean(false)));
    }

    @Test
    void findsServerVerifiedBounceNearItsMineshaft() {
        var request =
                new SearchRequest(
                        0L,
                        GameVersion.V26_2,
                        -1664,
                        -512,
                        32,
                        1,
                        0,
                        1,
                        true,
                        List.of("minecraft:music_disc_bounce"));
        var hits = new ArrayList<SearchHit>();
        var report =
                engine.search(
                        request,
                        new SearchEngine.Listener() {
                            public void onHit(SearchHit hit) {
                                hits.add(hit);
                            }

                            public void onProgress(SearchProgress progress) {}
                        },
                        new AtomicBoolean(false));
        assertTrue(report.completed());
        assertTrue(hits.stream().anyMatch(h -> h.x() == -1671 && h.y() == -20 && h.z() == -458));
        assertTrue(
                hits.stream().allMatch(SearchHit::estimated),
                "limited terrain simulation must remain labelled");
    }

    @Test
    void enabledDungeonSearchFindsNativeChestAndLabelsItsApproximation() {
        var request =
                new SearchRequest(
                        0L,
                        GameVersion.V26_2,
                        -56,
                        152,
                        16,
                        1,
                        0,
                        1,
                        false,
                        List.of("minecraft:music_disc_otherside"));
        var hits = new ArrayList<SearchHit>();
        var report =
                engine.search(
                        request,
                        new SearchEngine.Listener() {
                            public void onHit(SearchHit hit) {
                                hits.add(hit);
                            }

                            public void onProgress(SearchProgress progress) {}
                        },
                        new AtomicBoolean(false));
        assertTrue(report.completed());
        assertTrue(hits.stream().anyMatch(h -> h.x() == -62 && h.y() == 35 && h.z() == 142));
        assertTrue(hits.stream().allMatch(SearchHit::estimated));
    }

    @Test
    void accurateTerrainSearchReachesTheFirstThreeBounceCartsFromOrigin() {
        var request =
                new SearchRequest(
                        0L,
                        GameVersion.V26_2,
                        0,
                        0,
                        5000,
                        3,
                        0,
                        1,
                        true,
                        List.of("minecraft:music_disc_bounce"));
        var hits = new ArrayList<SearchHit>();
        var report =
                engine.search(
                        request,
                        new SearchEngine.Listener() {
                            public void onHit(SearchHit hit) {
                                hits.add(hit);
                            }

                            public void onProgress(SearchProgress progress) {}
                        },
                        new AtomicBoolean(false));
        assertTrue(
                report.completed(),
                "native terrain must not exhaust the old budget before the first useful mineshaft");
        assertEquals(3, report.foundPerItem().get("minecraft:music_disc_bounce"));
        var expected = java.util.Set.of("-1671,-20,-458", "-1657,-16,-487", "-1657,-16,-492");
        assertTrue(
                hits.stream()
                        .allMatch(
                                hit -> expected.contains(hit.x() + "," + hit.y() + "," + hit.z())));
    }

    @Test
    void findsDuneTemplateAndPrizeSherdNearSpawn() {
        SearchRequest request =
                new SearchRequest(
                        0L,
                        GameVersion.V26_2,
                        0,
                        0,
                        6500,
                        1,
                        3,
                        1,
                        true,
                        List.of(
                                "minecraft:dune_armor_trim_smithing_template",
                                "minecraft:prize_pottery_sherd"));
        List<SearchHit> hits = new ArrayList<>();
        SearchReport report =
                engine.search(
                        request,
                        new SearchEngine.Listener() {
                            @Override
                            public void onHit(SearchHit hit) {
                                hits.add(hit);
                            }

                            @Override
                            public void onProgress(SearchProgress progress) {}
                        },
                        new AtomicBoolean(false));

        assertTrue(
                hits.stream()
                        .anyMatch(
                                h ->
                                        h.itemId()
                                                .equals(
                                                        "minecraft:dune_armor_trim_smithing_template")),
                "expected dune template, hits=" + hits);
        assertTrue(
                hits.stream().anyMatch(h -> h.itemId().equals("minecraft:prize_pottery_sherd")),
                "expected prize sherd, hits=" + hits);
        assertTrue(report.completed(), "quota should be met");
        assertTrue(report.totalElapsedNanos() > 0);
        assertTrue(
                report.foundPerItem().get("minecraft:dune_armor_trim_smithing_template") >= 2,
                "pyramid pool grants 2 at once, found=" + report.foundPerItem());
    }

    @Test
    void vaultSequenceHitCarriesNthOpen() {
        SearchRequest request =
                new SearchRequest(
                        0L,
                        GameVersion.V26_2,
                        0,
                        0,
                        3000,
                        1,
                        3,
                        1,
                        true,
                        List.of("minecraft:bolt_armor_trim_smithing_template"));
        List<SearchHit> hits = new ArrayList<>();
        engine.search(
                request,
                new SearchEngine.Listener() {
                    @Override
                    public void onHit(SearchHit hit) {
                        hits.add(hit);
                    }

                    @Override
                    public void onProgress(SearchProgress progress) {}
                },
                new AtomicBoolean(false));

        assertTrue(!hits.isEmpty(), "expected bolt template from vault sequence");
        SearchHit hit = hits.getFirst();
        assertTrue(
                hit.method().id.equals("vault") || hit.method().id.equals("chest"),
                "unexpected method, hits=" + hits);
        if (hit.method().id.equals("vault")) {
            assertTrue(hit.nth() >= 1, "vault hit must carry the open index, hits=" + hits);
        } else {
            assertEquals(-1, hit.nth(), "chest hits carry no sequence index, hits=" + hits);
        }
    }

    @Test
    void stopEarlyWhenQuotaMet() {
        SearchRequest request =
                new SearchRequest(
                        0L,
                        GameVersion.V26_2,
                        0,
                        0,
                        6500,
                        1,
                        3,
                        1,
                        true,
                        List.of("minecraft:dune_armor_trim_smithing_template"));
        List<SearchHit> hits = new ArrayList<>();
        SearchReport report =
                engine.search(
                        request,
                        new SearchEngine.Listener() {
                            @Override
                            public void onHit(SearchHit hit) {
                                hits.add(hit);
                            }

                            @Override
                            public void onProgress(SearchProgress progress) {}
                        },
                        new AtomicBoolean(false));
        assertTrue(report.completed());
        assertTrue(
                hits.stream()
                        .anyMatch(
                                h ->
                                        h.itemId()
                                                .equals(
                                                        "minecraft:dune_armor_trim_smithing_template")),
                "expected the dune template, hits=" + hits);
        assertTrue(
                report.foundPerItem().get("minecraft:dune_armor_trim_smithing_template") >= 1,
                "quota should be met, found=" + report.foundPerItem());
    }
}
