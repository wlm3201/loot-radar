package com.lootradar.dump;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.PotDecorations;

final class PotItems {
    static Iterable<Item> ordered(PotDecorations decorations) {
        return List.of(
                        decorations.back(),
                        decorations.left(),
                        decorations.right(),
                        decorations.front())
                .stream()
                .map(item -> item.map(template -> template.item().value()).orElse(Items.BRICK))
                .toList();
    }
}
