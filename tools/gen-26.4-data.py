"""由 data/26.3 生成 data/26.4，并核对 26.4 client jar 内的 structure_set。

追加 Flurry 唱片（雪屋地下室）与 dataVersion；最后打印 jar 中引用该唱片的战利品表供人工复核。
"""
import argparse
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--jar", required=True, help="26.4 client jar 路径")
args = parser.parse_args()
JAR = Path(args.jar)

src = ROOT / "data" / "26.3"
dst = ROOT / "data" / "26.4"
dst.mkdir(exist_ok=True)

structures = json.loads((src / "structures.json").read_text(encoding="utf-8"))
items = json.loads((src / "items.json").read_text(encoding="utf-8"))

z = zipfile.ZipFile(JAR)
jar_sets = {}
for name in z.namelist():
    if name.startswith("data/minecraft/worldgen/structure_set/") and name.endswith(".json"):
        sid = name.split("/")[-1][:-5]
        jar_sets[sid] = json.loads(z.read(name))

KEYMAP = {
    "type": "type",
    "salt": "salt",
    "spacing": "spacing",
    "separation": "separation",
    "spread_type": "spreadType",
    "frequency": "frequency",
    "frequency_reduction_method": "frequencyReductionMethod",
    "locate_offset": "locateOffset",
    "count": "count",
    "distance": "distance",
    "spread": "spread",
}
diffs = []
seen = set()
for entry in structures["structureSets"]:
    sid = entry["id"].split(":")[-1]
    jar = jar_sets.get(sid)
    if jar is None:
        diffs.append(f"MISSING in 26.4 jar: {sid}")
        continue
    seen.add(sid)
    p_in = jar["placement"]
    p_out = entry["placement"]
    for jk, ok in KEYMAP.items():
        if jk in p_in:
            value = p_in[jk]
            if isinstance(value, str) and value.startswith("minecraft:"):
                value = value.split(":", 1)[1]
            if ok in p_out and p_out[ok] != value:
                diffs.append(f"{sid}.{ok}: {p_out[ok]} -> {value}")
            entry["placement"][ok] = value
    jar_members = [(m["structure"].split(":")[-1], m.get("weight", 1)) for m in jar["structures"]]
    our_members = [(m["id"].split(":")[-1], m.get("weight", 1)) for m in entry["structures"]]
    if jar_members != our_members:
        diffs.append(f"{sid} members: {our_members} -> {jar_members}")
        entry["structures"] = [
            {"id": "minecraft:" + n if ":" not in n else n, "weight": w} for n, w in jar_members
        ]
for sid in jar_sets:
    if sid not in seen:
        diffs.append(f"NEW in 26.4 jar (not in ours): {sid}")

for row in structures["structures"]:
    if row["id"] == "minecraft:igloo":
        row["notes"] = "雪屋地下室箱（chests/igloo_chest）；26.4 起含音乐唱片 Flurry（30% 池）"

structures["version"] = "26.4"
if "dataVersion" in structures:
    structures["dataVersion"] = 5127

(dst / "structures.json").write_text(
    json.dumps(structures, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

ids = [i["id"] for i in items["items"]]
flurry_added = "minecraft:music_disc_flurry" not in ids
if flurry_added:
    items["items"].append({
        "id": "minecraft:music_disc_flurry",
        "zhName": "音乐唱片（Flurry）",
        "group": "record",
        "sources": [
            {
                "label": "雪屋",
                "structures": ["minecraft:igloo"],
                "method": "chest",
                "pieces": ["雪屋地下室"],
            }
        ],
    })
items["version"] = "26.4"
(dst / "items.json").write_text(
    json.dumps(items, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

print("sets verified:", len(seen), " diffs:", len(diffs))
for d in diffs:
    print(" ", d)
print("items:", len(items["items"]), " (flurry added)" if flurry_added else "")

hits = []
for name in z.namelist():
    if name.startswith("data/minecraft/loot_table/") and name.endswith(".json"):
        body = z.read(name).decode("utf-8", "replace")
        if "music_disc_flurry" in body:
            hits.append(name)
print("flurry tables:", hits)
