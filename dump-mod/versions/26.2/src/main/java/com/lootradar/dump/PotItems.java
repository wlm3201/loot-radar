package com.lootradar.dump;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.PotDecorations;

final class PotItems {
    static Iterable<Item> ordered(PotDecorations decorations) {
        return decorations.ordered();
    }
}
