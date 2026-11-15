package dev.lootradar.app.tools;

import dev.lootradar.core.version.GameVersion;
import dev.lootradar.core.version.VersionRegistry;
import dev.lootradar.engine.VanillaRuntime;

/**
 * 校准辅助：把 {@code structures.json} 里钉死的 (step, index) 与当前原生注册表 计算值逐项对比，输出 MISMATCH 时提示需要重跑 {@code
 * pin-decoration-indices.py}。
 */
public final class DecorationCoordsProbe {
    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        try (var runtime = VanillaRuntime.load(seed)) {
            var version = GameVersion.parse(net.minecraft.SharedConstants.getCurrentVersion().id());
            var registry = VersionRegistry.load(version, VersionRegistry.resolveDataRoot());
            for (var entry : registry.structures().entrySet()) {
                var spec = entry.getValue();
                if (spec.decorationIndex() == null) continue;
                var live = runtime.structureDecorationCoordinates(entry.getKey());
                boolean same =
                        live.step() == spec.generationStep()
                                && live.indexWithinStep() == spec.decorationIndex();
                System.out.println(
                        entry.getKey()
                                + " pinned="
                                + spec.generationStep()
                                + "/"
                                + spec.decorationIndex()
                                + " live="
                                + live.step()
                                + "/"
                                + live.indexWithinStep()
                                + (same ? "" : "   << MISMATCH"));
            }
        }
    }
}
