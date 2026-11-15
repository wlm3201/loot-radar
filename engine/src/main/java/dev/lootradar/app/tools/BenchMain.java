package dev.lootradar.app.tools;

import dev.lootradar.core.catalog.ItemCatalog;
import dev.lootradar.core.search.SearchHit;
import dev.lootradar.core.search.SearchProgress;
import dev.lootradar.core.search.SearchReport;
import dev.lootradar.core.search.SearchRequest;
import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.VanillaLootOracle;
import dev.lootradar.engine.VanillaRuntime;
import dev.lootradar.engine.search.SearchEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 搜索耗时基准：固定 26.2 / 种子 0，跑 7 组目标物品配置，输出 {@code docs/bench-report.{md,csv}}。可用 {@code -Pradius=<r>}
 * 改半径。
 */
public final class BenchMain {

    public static void main(String[] args) throws Exception {
        int radius = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        VersionRegistry versions =
                VersionRegistry.load(GameVersion.V26_2, VersionRegistry.resolveDataRoot());
        ItemCatalog catalog =
                ItemCatalog.load(VersionRegistry.resolveDataRoot(), GameVersion.V26_2.id);

        List<List<String>> profiles =
                List.of(
                        List.of("minecraft:music_disc_bounce"),
                        List.of("minecraft:host_armor_trim_smithing_template"),
                        List.of("minecraft:snort_pottery_sherd"),
                        List.of("minecraft:arms_up_pottery_sherd"),
                        List.of("minecraft:coast_armor_trim_smithing_template"),
                        List.of("minecraft:flow_pottery_sherd"),
                        List.of("minecraft:heart_of_the_sea"));

        StringBuilder csv =
                new StringBuilder("profile,items,radius,structures,containers,totalMs\n");
        StringBuilder md =
                new StringBuilder("# bench 报告\n\n半径 ")
                        .append(radius)
                        .append("，种子 0，每物品配额 1\n\n")
                        .append("| 配置 | 物品数 | 结构 | 容器 | 总耗时 |\n| --- | --- | --- | --- | --- |\n");

        try (VanillaRuntime runtime = VanillaRuntime.load(0L)) {
            SearchEngine engine =
                    new SearchEngine(runtime, new VanillaLootOracle(runtime), versions, catalog);
            for (List<String> itemIds : profiles) {
                SearchRequest request =
                        new SearchRequest(
                                0L, GameVersion.V26_2, 0, 0, radius, 1, 3, 1, true, itemIds);
                long startedAt = System.nanoTime();
                SearchReport report =
                        engine.search(
                                request,
                                new SearchEngine.Listener() {
                                    @Override
                                    public void onHit(SearchHit hit) {}

                                    @Override
                                    public void onProgress(SearchProgress progress) {}
                                },
                                new AtomicBoolean(false));
                long millis = (System.nanoTime() - startedAt) / 1_000_000;
                String label = itemIds.size() == 1 ? itemIds.getFirst() : itemIds.size() + " items";
                csv.append(label)
                        .append(',')
                        .append(itemIds.size())
                        .append(',')
                        .append(radius)
                        .append(',')
                        .append(report.structuresScanned())
                        .append(',')
                        .append(report.containersReplayed())
                        .append(',')
                        .append(millis)
                        .append('\n');
                md.append("| ")
                        .append(label)
                        .append(" | ")
                        .append(itemIds.size())
                        .append(" | ")
                        .append(report.structuresScanned())
                        .append(" | ")
                        .append(report.containersReplayed())
                        .append(" | ")
                        .append(millis)
                        .append(" ms |\n");
                System.out.println("bench: " + label + " -> " + millis + " ms");
            }
        }

        Files.createDirectories(Path.of("docs"));
        Files.writeString(Path.of("docs/bench-report.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("docs/bench-report.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.println("reports -> docs/bench-report.{md,csv}");
    }
}
