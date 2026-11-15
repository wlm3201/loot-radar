package dev.lootradar.core.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lootradar.core.structure.PlacementSpec;
import dev.lootradar.core.structure.StructureSpec;
import org.junit.jupiter.api.Test;

/** 校验 26.2 目录中的原版放置常量，以及非法 JSON 必须抛 DataFormatException。 */
class VersionRegistryTest {

    @Test
    void loadsVersion262WithVanillaPlacementParams() {
        VersionRegistry registry =
                VersionRegistry.load(GameVersion.V26_2, VersionRegistry.resolveDataRoot());

        assertEquals(GameVersion.V26_2, registry.version());

        StructureSpec pyramid = registry.structure("minecraft:desert_pyramid");
        assertEquals("沙漠神殿", pyramid.zhName());
        assertEquals("overworld", pyramid.dimension());

        PlacementSpec placement = registry.sets().get("minecraft:desert_pyramids").placement();
        assertEquals(PlacementSpec.Type.RANDOM_SPREAD, placement.type());
        assertEquals(14357617, placement.salt());
        assertEquals(32, placement.spacing());
        assertEquals(8, placement.separation());
        assertEquals("linear", placement.spreadType());

        PlacementSpec stronghold = registry.sets().get("minecraft:strongholds").placement();
        assertEquals(PlacementSpec.Type.CONCENTRIC_RINGS, stronghold.type());
        assertEquals(128, stronghold.count());
        assertEquals(32, stronghold.distance());
        assertEquals(3, stronghold.spread());

        PlacementSpec nether = registry.sets().get("minecraft:nether_complexes").placement();
        assertEquals(30084232, nether.salt());
        assertEquals(
                2,
                registry.sets().get("minecraft:nether_complexes").structures().stream()
                        .filter(m -> m.id().equals("minecraft:fortress"))
                        .findFirst()
                        .orElseThrow()
                        .weight());
        assertEquals(
                3,
                registry.sets().get("minecraft:nether_complexes").structures().stream()
                        .filter(m -> m.id().equals("minecraft:bastion_remnant"))
                        .findFirst()
                        .orElseThrow()
                        .weight());

        StructureSpec dungeon = registry.structure("minecraft:monster_room");
        assertEquals("dungeon", dungeon.skipKey());
        assertNotNull(registry.structure("minecraft:stronghold").skipKey());
    }

    @Test
    void rejectsSpacingNotLargerThanSeparation() {
        String json =
                """
                {
                  "version": "26.2",
                  "structureSets": [{
                    "id": "bad:set",
                    "placement": { "type": "random_spread", "salt": 1, "spacing": 4, "separation": 4 },
                    "structures": [{ "id": "bad:structure", "weight": 1 }]
                  }],
                  "structures": [
                    { "id": "bad:structure", "zhName": "坏", "dimension": "overworld", "set": "bad:set" }
                  ]
                }
                """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(
                DataFormatException.class, () -> VersionRegistry.parse(GameVersion.V26_2, root));
    }

    @Test
    void rejectsVersionMismatch() {
        String json =
                """
                {
                  "version": "26.3",
                  "structureSets": [{
                    "id": "bad:set",
                    "placement": { "type": "random_spread", "salt": 1, "spacing": 8, "separation": 4 },
                    "structures": [{ "id": "bad:structure", "weight": 1 }]
                  }],
                  "structures": [
                    { "id": "bad:structure", "zhName": "坏", "dimension": "overworld", "set": "bad:set" }
                  ]
                }
                """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(
                DataFormatException.class, () -> VersionRegistry.parse(GameVersion.V26_2, root));
    }

    @Test
    void rejectsStructureNotInDeclaredSet() {
        String json =
                """
                {
                  "version": "26.2",
                  "structureSets": [{
                    "id": "bad:set",
                    "placement": { "type": "random_spread", "salt": 1, "spacing": 8, "separation": 4 },
                    "structures": [{ "id": "bad:other", "weight": 1 }]
                  }],
                  "structures": [
                    { "id": "bad:structure", "zhName": "坏", "dimension": "overworld", "set": "bad:set" }
                  ]
                }
                """;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertThrows(
                DataFormatException.class, () -> VersionRegistry.parse(GameVersion.V26_2, root));
    }
}
