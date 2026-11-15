package dev.lootradar.app.tools;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.FeatureScanner;
import dev.lootradar.engine.VanillaRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;

/** 沙漠水井普查：按 desert 群系与 1/1000 rarity 枚举候选区块，统计可疑沙子掉落。 */
public final class WellCensusMain {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        int radius = Integer.parseInt(args[1]);
        String dimension = "minecraft:" + args[2];
        try (var runtime = VanillaRuntime.load(seed)) {
            var version = GameVersion.parse(net.minecraft.SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            var spec = registry.structures().get("minecraft:desert_well");
            var live = runtime.featureDecorationCoordinates("minecraft:desert_well", dimension);
            System.out.println(
                    "decoration coordinates live="
                            + live
                            + " pinned="
                            + (spec == null || spec.featureIndex() == null
                                    ? "none"
                                    : spec.featureStep() + "/" + spec.featureIndex()));
            var generator = runtime.chunkGenerator(dimension);
            var censusSteps =
                    net.minecraft.world.level.biome.FeatureSorter.buildFeaturesPerStep(
                            java.util.List.copyOf(generator.getBiomeSource().possibleBiomes()),
                            b -> b.value().getGenerationSettings().features(),
                            true);
            var wellFeature = runtime.placedFeature("minecraft:desert_well");
            int censusStep = -1, censusIndex = -1;
            for (int step = 0; step < censusSteps.size(); step++) {
                int index = censusSteps.get(step).features().indexOf(wellFeature);
                if (index >= 0) {
                    censusStep = step;
                    censusIndex = index;
                    break;
                }
            }
            System.out.println(
                    "census-style coordinates step="
                            + censusStep
                            + " indexWithinStep="
                            + censusIndex);
            int bound = (radius + 31) / 16;
            int desert = 0, livePass = 0, pinnedPass = 0, rollPassAll = 0, wellsPlaced = 0;
            int rollInDesert = 0;
            for (int x = -bound; x <= bound; x++) {
                for (int z = -bound; z <= bound; z++) {
                    var p = new ChunkPos(x, z);
                    long bx = p.getMinBlockX() + 8L, bz = p.getMinBlockZ() + 8L;
                    if (bx * bx + bz * bz > (long) (radius + 32) * (radius + 32)) continue;
                    boolean isDesert =
                            runtime.biomeAt(
                                            dimension,
                                            new BlockPos(
                                                    p.getMinBlockX() + 8, 64, p.getMinBlockZ() + 8))
                                    .is(Biomes.DESERT);
                    if (isDesert) desert++;
                    boolean roll =
                            FeatureScanner.rarityAllows(
                                    runtime, "minecraft:desert_well", dimension, p, spec.rarity());
                    if (roll) {
                        rollPassAll++;
                        if (isDesert) rollInDesert++;
                    }
                    if (isDesert && roll) {
                        livePass++;
                        if (FeatureScanner.rarityAllows(
                                runtime,
                                "minecraft:desert_well",
                                dimension,
                                p,
                                spec.rarity(),
                                registry)) pinnedPass++;
                    }
                    if (roll) {
                        for (var source :
                                FeatureScanner.scan(
                                        runtime, "minecraft:desert_well", dimension, p)) {
                            if (source.lootTable().contains("desert_well")) {
                                wellsPlaced++;
                                break;
                            }
                        }
                    }
                }
            }
            System.out.println(
                    "desert chunks="
                            + desert
                            + " roll passes (all/indesert)="
                            + rollPassAll
                            + "/"
                            + rollInDesert
                            + " real wells placed="
                            + wellsPlaced
                            + " pinned agrees="
                            + (pinnedPass == livePass));
        }
    }
}
