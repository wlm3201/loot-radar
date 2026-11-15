package com.lootradar.dump;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;

final class VersionCompat {
    private VersionCompat() {}

    static boolean isValidStart(StructureStart start) {
        return start != null;
    }

}
