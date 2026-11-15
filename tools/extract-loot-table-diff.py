"""提取各版本战利品表差异，写入 data/<version>/loot_tables/。

以 26.2 为基线，只写 NEW/DIFF 表并归一到 26.2 的 codec 结构。
建议先跑 gen_version_data.py（它只覆盖两个 catalog JSON，不会抹掉已提取的覆盖表）。
"""
import argparse
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RELEVANT = ("chests/", "archaeology/", "barrels/", "pots/",
            "gameplay/fishing/fish", "vaults/", "spawners/", "entities/elder_guardian")

UNTRANSLATABLE = {
    "entities/creeper", "entities/ghast", "entities/magma_cube",
    "entities/slime", "entities/turtle",
    "chests/abandoned_camp_common_chest", "chests/abandoned_camp_secret_chest",
}


def raw_tables(jar: Path) -> dict[str, dict]:
    with zipfile.ZipFile(jar) as z:
        return {
            name[len("data/minecraft/loot_table/"):-len(".json")]: json.loads(z.read(name))
            for name in z.namelist()
            if name.startswith("data/minecraft/loot_table/") and name.endswith(".json")
        }


def norm_conditions(cond) -> list:
    items = cond if isinstance(cond, list) else [cond]
    out = []
    for c in items:
        if isinstance(c, str):
            out.append({"condition": c})
            continue
        o = {k: v for k, v in c.items() if k not in ("type", "terms", "term")}
        o["condition"] = c["type"]
        if "terms" in c:
            o["terms"] = norm_conditions(c["terms"])
        if "term" in c:
            o["term"] = norm_conditions(c["term"])[0]
        out.append(o)
    return out


def norm_function(f: dict) -> dict:
    out = {k: v for k, v in f.items() if k not in ("type", "condition", "functions", "on_fail", "on_pass", "on_success")}
    out["function"] = f.get("type", f.get("function"))
    if out.get("add") is False:
        del out["add"]
    if "condition" in f:
        out["conditions"] = norm_conditions(f["condition"])
    if "functions" in f:
        out["functions"] = [norm_function(x) for x in f["functions"]]
    for key in ("on_fail", "on_pass", "on_success"):
        if key in f:
            value = f[key]
            out[key] = norm_function(value) if isinstance(value, dict) else value
    if isinstance(out.get("destination"), str) and out["destination"].startswith("#"):
        del out["destination"]
    if isinstance(out.get("decoration"), str):
        out["decoration"] = "minecraft:red_x"
    return out


def norm_modifier(node) -> list:
    items = node if isinstance(node, list) else [node]
    return [norm_function(f) for f in items]


def norm_entry(e: dict) -> dict:
    out = {k: v for k, v in e.items() if k not in ("modifier", "condition", "children")}
    if out.get("weight") == 1:
        del out["weight"]
    if out.get("quality") == 0:
        del out["quality"]
    if "modifier" in e:
        out["functions"] = norm_modifier(e["modifier"])
    if "condition" in e:
        out["conditions"] = norm_conditions(e["condition"])
    if "children" in e:
        out["children"] = [norm_entry(c) for c in e["children"]]
    if out.get("type") == "minecraft:tag" and "items" in out and "name" not in out:
        out["name"] = out.pop("items")
    if "functions" in out:
        out["functions"] = [norm_function(x) for x in out["functions"]]
    return out


def norm_pool(p: dict) -> dict:
    out = {k: v for k, v in p.items() if k not in ("modifier", "condition", "entries")}
    if out.get("bonus_rolls") == 0:
        del out["bonus_rolls"]
    if "modifier" in p:
        out["functions"] = norm_modifier(p["modifier"])
    if "condition" in p:
        out["conditions"] = norm_conditions(p["condition"])
    out["entries"] = [norm_entry(e) for e in p.get("entries", [])]
    if "functions" in out:
        out["functions"] = [norm_function(x) for x in out["functions"]]
    return out


def normalize(table: dict) -> dict:
    """把单张表归一成 26.2 结构；对 26.2 输入是幂等的。"""
    out = {k: v for k, v in table.items() if k not in ("modifier", "condition", "pools")}
    if "modifier" in table:
        out["functions"] = norm_modifier(table["modifier"])
    if "condition" in table:
        out["conditions"] = norm_conditions(table["condition"])
    out["pools"] = [norm_pool(p) for p in table.get("pools", [])]
    if "functions" in out:
        out["functions"] = [norm_function(x) for x in out["functions"]]
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-dir", required=True, help=".minecraft 目录")
    args = parser.parse_args()
    versions_dir = Path(args.minecraft_dir) / "versions"
    jars = {
        "26.1.2": versions_dir / "26.1.2-Fabric" / "26.1.2-Fabric.jar",
        "26.2": versions_dir / "26.2-Fabric" / "26.2-Fabric.jar",
        "26.3": versions_dir / "26.3-Fabric" / "26.3-Fabric.jar",
        "26.4": versions_dir / "26.4-snapshot-7-Fabric" / "26.4-snapshot-7-Fabric.jar",
    }
    base = {key: normalize(t) for key, t in raw_tables(jars["26.2"]).items()}
    print(f"26.2 tables: {len(base)}")
    for ver in ("26.1.2", "26.3", "26.4"):
        cur = raw_tables(jars[ver])
        dst = ROOT / "data" / ver / "loot_tables"
        if dst.exists():
            shutil.rmtree(dst)
        count = 0
        for key in sorted(cur):
            if not key.startswith(RELEVANT) or key in UNTRANSLATABLE:
                continue
            normalized = normalize(cur[key])
            if key in base and base[key] == normalized:
                continue
            print(f"  {'NEW ' if key not in base else 'DIFF'} {key}")
            path = dst / f"{key}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(normalized, ensure_ascii=False), encoding="utf-8")
            count += 1
        print(f"{ver}: stored {count} semantic override tables in {dst}")


if __name__ == "__main__":
    main()
