**English** | [简体中文](README.md)

# Loot Radar · Loot Finder

A loot finder for Minecraft Java Edition. Enter the game version, world seed, center coordinates, search radius, and target items, and it outputs the n nearest locations where those items can be obtained. Supports versions 26.1.2, 26.2, 26.3, and 26.4.

![ui](docs/ui.jpg)

# Structures

| Structure                          | Dimension  | Method       | Coverage | Accuracy |
| ---------------------------------- | ---------- | ------------ | -------- | -------- |
| Bastion Remnant Treasure Room      | The Nether | Chest        | ✅       | 100%     |
| Bastion Remnant Bridge             | The Nether | Chest        | ✅       | 100%     |
| Bastion Remnant Spire              | The Nether | Chest        | ✅       | 100%     |
| Bastion Remnant Hoglin Stable      | The Nether | Chest        | ✅       | 100%     |
| Shipwreck Treasure Chest           | Overworld  | Chest        | ✅       | 100%     |
| Shipwreck Supply Chest             | Overworld  | Chest        | ✅       | 100%     |
| Shipwreck Map Chest                | Overworld  | Chest        | ✅       | 100%     |
| Jungle Pyramid                     | Overworld  | Chest        | ✅       | 100%     |
| Village Weaponsmith's House        | Overworld  | Chest        | ✅       | 95.6%    |
| Large Ocean Ruins                  | Overworld  | Chest        | ✅       | 98.5%    |
| Ruined Portal                      | Overworld  | Chest        | ✅       | 99.7%    |
| Ruined Portal                      | The Nether | Chest        | ✅       | 100%     |
| Trail Ruins                        | Overworld  | Archaeology  | ✅       | 100%     |
| Cold Ocean Ruins                   | Overworld  | Archaeology  | ✅       | 99.3%    |
| Woodland Mansion                   | Overworld  | Chest        | ✅       | 100%     |
| Abandoned Mineshaft (Sulfur Caves) | Overworld  | Minecart     | ✅       | 100%     |
| Pillager Outpost                   | Overworld  | Chest        | ✅       | 100%     |
| Buried Treasure                    | Overworld  | Chest        | ✅       | 100%     |
| End City                           | The End    | Chest        | ✅       | 100%     |
| Warm Ocean Ruins                   | Overworld  | Archaeology  | ✅       | 97.8%    |
| Desert Pyramid                     | Overworld  | Archaeology  | ✅       | 93.5%    |
| Desert Pyramid                     | Overworld  | Chest        | ✅       | 100%     |
| Desert Well                        | Overworld  | Archaeology  | ✅       | 100%     |
| Trial Chambers Vault               | Overworld  | Vault        | ✅       | 100%     |
| Trial Chambers Ominous Vault       | Overworld  | Vault        | ✅       | 100%     |
| Trial Chambers Pots                | Overworld  | Pot          | ✅       | 93.8%    |
| Trial Chambers Corridor Pots       | Overworld  | Corridor Pot | ✅       | 99.9%    |
| Monster Room                       | Overworld  | Chest        | ✅       | 97.2%    |
| Nether Fortress                    | The Nether | Chest        | ✅       | 100%     |
| Small Ocean Ruins                  | Overworld  | Chest        | ✅       | 98.7%    |
| Stronghold Library                 | Overworld  | Chest        | ✅       | 100%     |
| Stronghold Corridor                | Overworld  | Chest        | ✅       | 98.0%    |
| Ancient City                       | Overworld  | Chest        | ✅       | 98.1%    |
| Elder Guardian                     | Overworld  | Mob          | ✅       | 100%     |
| Igloo                              | Overworld  | Chest        | ✅       | 100%     |

> Accuracy figures come from sampling checks on version 26.4.

# Items

| Pottery Sherd            | Structure           | Method      | Coverage |
| ------------------------ | ------------------- | ----------- | -------- |
| Friend Pottery Sherd     | Trail Ruins         | Archaeology | ✅       |
| Prize Pottery Sherd      | Desert Pyramid      | Archaeology | ✅       |
| Snort Pottery Sherd      | Warm Ocean Ruins    | Archaeology | ✅       |
| Heartbreak Pottery Sherd | Trail Ruins         | Archaeology | ✅       |
| Danger Pottery Sherd     | Trail Ruins         | Archaeology | ✅       |
| Skull Pottery Sherd      | Desert Pyramid      | Archaeology | ✅       |
| Explorer Pottery Sherd   | Cold Ocean Ruins    | Archaeology | ✅       |
| Shelter Pottery Sherd    | Warm Ocean Ruins    | Archaeology | ✅       |
| Sheaf Pottery Sherd      | Trail Ruins         | Archaeology | ✅       |
| Burn Pottery Sherd       | Trail Ruins         | Archaeology | ✅       |
| Blade Pottery Sherd      | Cold Ocean Ruins    | Archaeology | ✅       |
| Howl Pottery Sherd       | Trail Ruins         | Archaeology | ✅       |
| Arms Up Pottery Sherd    | Desert Well         | Archaeology | ✅       |
| Brewer Pottery Sherd     | Desert Well         | Archaeology | ✅       |
| Archer Pottery Sherd     | Desert Pyramid      | Archaeology | ✅       |
| Plenty Pottery Sherd     | Cold Ocean Ruins    | Archaeology | ✅       |
| Angler Pottery Sherd     | Warm Ocean Ruins    | Archaeology | ✅       |
| Miner Pottery Sherd      | Desert Pyramid      | Archaeology | ✅       |
| Mourner Pottery Sherd    | Cold Ocean Ruins    | Archaeology | ✅       |
| Heart Pottery Sherd      | Trail Ruins         | Archaeology | ✅       |
| Flow Pottery Sherd       | Trial Chambers Pots | Pot         | ✅       |
| Guster Pottery Sherd     | Trial Chambers Pots | Pot         | ✅       |
| Scrape Pottery Sherd     | Trial Chambers Pots | Pot         | ✅       |

| Smithing Template    | Structure                     | Method      | Coverage |
| -------------------- | ----------------------------- | ----------- | -------- |
| Netherite Upgrade    | Bastion Remnant Spire         | Chest       | ✅       |
| Netherite Upgrade    | Bastion Remnant Treasure Room | Chest       | ✅       |
| Netherite Upgrade    | Bastion Remnant Hoglin Stable | Chest       | ✅       |
| Netherite Upgrade    | Bastion Remnant Bridge        | Chest       | ✅       |
| Coast Armor Trim     | Shipwreck Supply Chest        | Chest       | ✅       |
| Coast Armor Trim     | Shipwreck Treasure Chest      | Chest       | ✅       |
| Coast Armor Trim     | Shipwreck Map Chest           | Chest       | ✅       |
| Wild Armor Trim      | Jungle Pyramid                | Chest       | ✅       |
| Spire Armor Trim     | End City                      | Chest       | ✅       |
| Ward Armor Trim      | Ancient City                  | Chest       | ✅       |
| Rib Armor Trim       | Nether Fortress               | Chest       | ✅       |
| Vex Armor Trim       | Woodland Mansion              | Chest       | ✅       |
| Dune Armor Trim      | Desert Pyramid                | Chest       | ✅       |
| Sentry Armor Trim    | Pillager Outpost              | Chest       | ✅       |
| Flow Armor Trim      | Trial Chambers Ominous Vault  | Vault       | ✅       |
| Bolt Armor Trim      | Trial Chambers Vault          | Vault       | ✅       |
| Eye Armor Trim       | Stronghold Corridor           | Chest       | ✅       |
| Eye Armor Trim       | Stronghold Library            | Chest       | ✅       |
| Silence Armor Trim   | Ancient City                  | Chest       | ✅       |
| Snout Armor Trim     | Bastion Remnant Spire         | Chest       | ✅       |
| Snout Armor Trim     | Bastion Remnant Treasure Room | Chest       | ✅       |
| Snout Armor Trim     | Bastion Remnant Hoglin Stable | Chest       | ✅       |
| Snout Armor Trim     | Bastion Remnant Bridge        | Chest       | ✅       |
| Host Armor Trim      | Trail Ruins                   | Archaeology | ✅       |
| Raiser Armor Trim    | Trail Ruins                   | Archaeology | ✅       |
| Shaper Armor Trim    | Trail Ruins                   | Archaeology | ✅       |
| Wayfinder Armor Trim | Trail Ruins                   | Archaeology | ✅       |
| Tide Armor Trim      | Elder Guardian                | Mob         | ✅       |

| Music Disc                       | Structure                          | Method       | Coverage |
| -------------------------------- | ---------------------------------- | ------------ | -------- |
| Disc Fragment (5)                | Ancient City                       | Chest        | ✅       |
| Music Disc (Bounce)              | Abandoned Mineshaft (Sulfur Caves) | Minecart     | ✅       |
| Music Disc (Creator (Music Box)) | Trial Chambers Corridor Pots       | Corridor Pot | ✅       |
| Music Disc (Creator)             | Trial Chambers Ominous Vault       | Vault        | ✅       |
| Music Disc (otherside)           | Ancient City                       | Chest        | ✅       |
| Music Disc (otherside)           | Monster Room                       | Chest        | ✅       |
| Music Disc (otherside)           | Stronghold Corridor                | Chest        | ✅       |
| Music Disc (Pigstep)             | Bastion Remnant Spire              | Chest        | ✅       |
| Music Disc (Precipice)           | Trial Chambers Vault               | Chest        | ✅       |
| Music Disc (Relic)               | Trail Ruins                        | Archaeology  | ✅       |
| Music Disc (Flurry)              | Igloo                              | Chest        | ✅       |

| Armor                  | Structure                   | Method | Coverage |
| ---------------------- | --------------------------- | ------ | -------- |
| Iron Nautilus Armor    | Shipwreck Supply Chest      | Chest  | ✅       |
| Iron Nautilus Armor    | Buried Treasure             | Chest  | ✅       |
| Iron Nautilus Armor    | Large Ocean Ruins           | Chest  | ✅       |
| Iron Nautilus Armor    | Small Ocean Ruins           | Chest  | ✅       |
| Iron Nautilus Armor    | Shipwreck Treasure Chest    | Chest  | ✅       |
| Iron Nautilus Armor    | Shipwreck Map Chest         | Chest  | ✅       |
| Copper Nautilus Armor  | Shipwreck Supply Chest      | Chest  | ✅       |
| Copper Nautilus Armor  | Buried Treasure             | Chest  | ✅       |
| Copper Nautilus Armor  | Large Ocean Ruins           | Chest  | ✅       |
| Copper Nautilus Armor  | Small Ocean Ruins           | Chest  | ✅       |
| Copper Nautilus Armor  | Shipwreck Treasure Chest    | Chest  | ✅       |
| Copper Nautilus Armor  | Shipwreck Map Chest         | Chest  | ✅       |
| Golden Nautilus Armor  | Shipwreck Supply Chest      | Chest  | ✅       |
| Golden Nautilus Armor  | Buried Treasure             | Chest  | ✅       |
| Golden Nautilus Armor  | Large Ocean Ruins           | Chest  | ✅       |
| Golden Nautilus Armor  | Small Ocean Ruins           | Chest  | ✅       |
| Golden Nautilus Armor  | Shipwreck Treasure Chest    | Chest  | ✅       |
| Golden Nautilus Armor  | Shipwreck Map Chest         | Chest  | ✅       |
| Diamond Nautilus Armor | Shipwreck Supply Chest      | Chest  | ✅       |
| Diamond Nautilus Armor | Buried Treasure             | Chest  | ✅       |
| Diamond Nautilus Armor | Large Ocean Ruins           | Chest  | ✅       |
| Diamond Nautilus Armor | Small Ocean Ruins           | Chest  | ✅       |
| Diamond Nautilus Armor | Shipwreck Treasure Chest    | Chest  | ✅       |
| Diamond Nautilus Armor | Shipwreck Map Chest         | Chest  | ✅       |
| Iron Horse Armor       | End City                    | Chest  | ✅       |
| Iron Horse Armor       | Monster Room                | Chest  | ✅       |
| Iron Horse Armor       | Desert Pyramid              | Chest  | ✅       |
| Iron Horse Armor       | Stronghold Corridor         | Chest  | ✅       |
| Iron Horse Armor       | Village Weaponsmith's House | Chest  | ✅       |
| Iron Horse Armor       | Nether Fortress             | Chest  | ✅       |
| Iron Horse Armor       | Jungle Pyramid              | Chest  | ✅       |
| Copper Horse Armor     | End City                    | Chest  | ✅       |
| Copper Horse Armor     | Monster Room                | Chest  | ✅       |
| Copper Horse Armor     | Desert Pyramid              | Chest  | ✅       |
| Copper Horse Armor     | Stronghold Corridor         | Chest  | ✅       |
| Copper Horse Armor     | Village Weaponsmith's House | Chest  | ✅       |
| Copper Horse Armor     | Nether Fortress             | Chest  | ✅       |
| Copper Horse Armor     | Jungle Pyramid              | Chest  | ✅       |
| Golden Horse Armor     | Ruined Portal               | Chest  | ✅       |
| Golden Horse Armor     | End City                    | Chest  | ✅       |
| Golden Horse Armor     | Monster Room                | Chest  | ✅       |
| Golden Horse Armor     | Desert Pyramid              | Chest  | ✅       |
| Golden Horse Armor     | Stronghold Corridor         | Chest  | ✅       |
| Golden Horse Armor     | Village Weaponsmith's House | Chest  | ✅       |
| Golden Horse Armor     | Nether Fortress             | Chest  | ✅       |
| Golden Horse Armor     | Jungle Pyramid              | Chest  | ✅       |
| Diamond Horse Armor    | Ancient City                | Chest  | ✅       |
| Diamond Horse Armor    | End City                    | Chest  | ✅       |
| Diamond Horse Armor    | Monster Room                | Chest  | ✅       |
| Diamond Horse Armor    | Desert Pyramid              | Chest  | ✅       |
| Diamond Horse Armor    | Stronghold Corridor         | Chest  | ✅       |
| Diamond Horse Armor    | Village Weaponsmith's House | Chest  | ✅       |
| Diamond Horse Armor    | Nether Fortress             | Chest  | ✅       |
| Diamond Horse Armor    | Jungle Pyramid              | Chest  | ✅       |

| Other                 | Structure                     | Method      | Coverage |
| --------------------- | ----------------------------- | ----------- | -------- |
| Flow Banner Pattern   | Trial Chambers Ominous Vault  | Vault       | ✅       |
| Guster Banner Pattern | Trial Chambers Vault          | Vault       | ✅       |
| Piglin Banner Pattern | Bastion Remnant Spire         | Chest       | ✅       |
| Heavy Core            | Trial Chambers Ominous Vault  | Vault       | ✅       |
| Heart of the Sea      | Buried Treasure               | Chest       | ✅       |
| Echo Shard            | Ancient City                  | Chest       | ✅       |
| Sniffer Egg           | Warm Ocean Ruins              | Archaeology | ✅       |
| Netherite Ingot       | Bastion Remnant Treasure Room | Chest       | ✅       |
