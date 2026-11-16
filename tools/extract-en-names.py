#!/usr/bin/env python3
"""从 Minecraft 源码树提取英文显示名，生成 web/src/i18n/names_en.json。

名称来源（按优先级）：
- 物品：`assets/minecraft/lang/en_us.json` 的 `item.minecraft.<id>` / `block.minecraft.<id>`；
  模板/旗帜图案取特化名 `<key>.new`，唱片取 `Music Disc (<key>.desc 副标题>)`，对齐 zhName 口径；
- 结构：`structure.minecraft.<id>`（26.x 语言文件中不存在该键，缺表时回落到
  本脚本内的 STRUCTURE_EN 兜底表，取值为原版通用英文结构名）；
- 分组：本脚本内的 GROUP_EN 兜底表（自定义分类，不在游戏语言文件中）。

用法：
    python tools/extract-en-names.py [--mc-src D:/Scripts/mc] [--report]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
OUT_FILE = ROOT / "web" / "src" / "i18n" / "names_en.json"
DEFAULT_MC_SRC = Path("D:/Scripts/mc")

# 26.x 语言文件缺少 structure.* 键；兜底采用原版通用英文结构名。
STRUCTURE_EN = {
    "ancient_city": "Ancient City",
    "bastion_remnant": "Bastion Remnant",
    "buried_treasure": "Buried Treasure",
    "desert_pyramid": "Desert Pyramid",
    "desert_well": "Desert Well",
    "end_city": "End City",
    "fortress": "Nether Fortress",
    "igloo": "Igloo",
    "jungle_pyramid": "Jungle Pyramid",
    "mansion": "Woodland Mansion",
    "mineshaft": "Abandoned Mineshaft",
    "mineshaft_mesa": "Abandoned Mineshaft (Badlands)",
    "monument": "Ocean Monument",
    "monster_room": "Monster Room",
    "nether_fossil": "Nether Fossil",
    "ocean_ruin_cold": "Cold Ocean Ruins",
    "ocean_ruin_warm": "Warm Ocean Ruins",
    "pillager_outpost": "Pillager Outpost",
    "ruined_portal": "Ruined Portal",
    "ruined_portal_desert": "Ruined Portal (Desert)",
    "ruined_portal_jungle": "Ruined Portal (Jungle)",
    "ruined_portal_mountain": "Ruined Portal (Mountain)",
    "ruined_portal_nether": "Ruined Portal (Nether)",
    "ruined_portal_ocean": "Ruined Portal (Ocean)",
    "ruined_portal_swamp": "Ruined Portal (Swamp)",
    "shipwreck": "Shipwreck",
    "shipwreck_beached": "Shipwreck (Beached)",
    "stronghold": "Stronghold",
    "swamp_hut": "Swamp Hut",
    "trail_ruins": "Trail Ruins",
    "trial_chambers": "Trial Chambers",
    "village_desert": "Village (Desert)",
    "village_plains": "Village (Plains)",
    "village_savanna": "Village (Savanna)",
    "village_snowy": "Village (Snowy)",
    "village_taiga": "Village (Taiga)",
}

# items.json 的自定义分类。
GROUP_EN = {
    "template": "Smithing Templates",
    "sherd": "Pottery Sherds",
    "record": "Music Discs",
    "armor": "Armor",
    "other": "Other",
}


def load_lang(mc_src: Path) -> dict[str, str]:
    """合并各版本 en_us.json；版本目录按名称排序，后者覆盖前者。"""
    merged: dict[str, str] = {}
    for version_dir in sorted(mc_src.iterdir()):
        if not version_dir.is_dir():
            continue
        for lang in sorted(version_dir.glob("minecraft-common-*/assets/minecraft/lang/en_us.json")):
            merged.update(json.loads(lang.read_text(encoding="utf-8")))
    return merged


def collect_ids() -> tuple[set[str], set[str], set[str]]:
    items: set[str] = set()
    structures: set[str] = set()
    groups: set[str] = set()
    for catalog in sorted(DATA_DIR.glob("*/items.json")):
        data = json.loads(catalog.read_text(encoding="utf-8"))
        groups.update(group["id"] for group in data.get("groups", []))
        for item in data.get("items", []):
            items.add(item["id"])
            for source in item.get("sources", []):
                structures.update(source.get("structures", []))
    for registry in sorted(DATA_DIR.glob("*/structures.json")):
        data = json.loads(registry.read_text(encoding="utf-8"))
        structures.update(entry["id"] for entry in data.get("structures", []))
    return items, structures, groups


def item_name(lang: dict[str, str], item_id: str) -> str | None:
    path = item_id.split(":", 1)[-1]
    for key in (f"item.minecraft.{path}", f"block.minecraft.{path}"):
        if key not in lang:
            continue
        # 模板/旗帜图案的特化名在 `<key>.new`（如 "Bolt Armor Trim"），与 zhName 口径一致。
        renamed = lang.get(f"{key}.new")
        if renamed:
            return renamed
        name = lang[key]
        # 唱片/唱片残片的副标题在 `<key>.desc`（如 "Lena Raine - Pigstep"），
        # zhName 形如「音乐唱片（Pigstep）」，这里对齐为 "Music Disc (Pigstep)"。
        desc = lang.get(f"{key}.desc", "")
        if " - " in desc:
            return f"{name} ({desc.split(' - ', 1)[1]})"
        return name
    return None


def structure_name(lang: dict[str, str], structure_id: str) -> tuple[str | None, str]:
    path = structure_id.split(":", 1)[-1]
    key = f"structure.minecraft.{path}"
    if key in lang:
        return lang[key], "lang"
    if path in STRUCTURE_EN:
        return STRUCTURE_EN[path], "fallback"
    return None, "missing"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mc-src", type=Path, default=DEFAULT_MC_SRC)
    parser.add_argument("--report", action="store_true", help="只打印提取结果，不写文件")
    args = parser.parse_args()

    if not args.mc_src.is_dir():
        print(f"minecraft source not found: {args.mc_src}", file=sys.stderr)
        return 1

    lang = load_lang(args.mc_src)
    item_ids, structure_ids, group_ids = collect_ids()

    items: dict[str, str] = {}
    missing_items: list[str] = []
    for item_id in sorted(item_ids):
        name = item_name(lang, item_id)
        if name is None:
            missing_items.append(item_id)
        else:
            items[item_id] = name

    structures: dict[str, str] = {}
    structure_sources: dict[str, str] = {}
    missing_structures: list[str] = []
    for structure_id in sorted(structure_ids):
        name, source = structure_name(lang, structure_id)
        if name is None:
            missing_structures.append(structure_id)
        else:
            structures[structure_id] = name
            structure_sources[structure_id] = source

    groups = {group_id: GROUP_EN[group_id] for group_id in sorted(group_ids) if group_id in GROUP_EN}
    missing_groups = sorted(group_ids - set(GROUP_EN))

    print(f"lang keys: {len(lang)}")
    print(f"items: {len(items)} extracted, missing {len(missing_items)}")
    for item_id in missing_items:
        print(f"  MISSING item {item_id}")
    print(f"structures: {len(structures)} extracted, missing {len(missing_structures)}")
    fallback = sum(1 for source in structure_sources.values() if source == "fallback")
    print(f"  from lang: {len(structures) - fallback}, from fallback table: {fallback}")
    for structure_id in missing_structures:
        print(f"  MISSING structure {structure_id}")
    print(f"groups: {len(groups)}, missing {missing_groups}")

    if not args.report:
        payload = {
            "$comment": "Generated by tools/extract-en-names.py from the Minecraft source lang files. Do not edit by hand.",
            "items": items,
            "structures": structures,
            "groups": groups,
        }
        OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
        OUT_FILE.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
            encoding="utf-8",
        )
        print(f"wrote {OUT_FILE}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
