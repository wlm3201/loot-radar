"""把 DecorationIndexDumpTest 校准出的装饰下标写回 data/<version>/structures.json。

必须在切换 -Pminecraft_version 重新跑完校准后执行；钉死下标可避免原生注册表
遍历顺序变化导致装饰随机流错位。
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

for version in ("26.1.2", "26.2", "26.3", "26.4"):
    index_file = ROOT / "engine" / "build" / f"decoration-index-{version}.json"
    if not index_file.is_file():
        index_file = ROOT / "build" / f"decoration-index-{version}.json"
    data_file = ROOT / "data" / version / "structures.json"
    if not index_file.is_file():
        print(f"{version}: no calibration file ({index_file.name}) - skipped")
        continue
    if not data_file.is_file():
        print(f"{version}: no structures.json - skipped")
        continue
    indices = json.loads(index_file.read_text(encoding="utf-8"))
    structures = json.loads(data_file.read_text(encoding="utf-8"))
    pinned = 0
    for row in structures["structures"]:
        entry = indices.get(row["id"])
        if entry is not None:
            row["generationStep"], row["decorationIndex"] = entry
            pinned += 1
        feature_entry = indices.get("feature:" + row["id"] + "@" + row["dimension"])
        if feature_entry is not None:
            row["featureStep"], row["featureIndex"] = feature_entry
            pinned += 1
        if row["id"] == "minecraft:monster_room":
            deep_entry = indices.get("feature:minecraft:monster_room_deep@" + row["dimension"])
            if deep_entry is not None:
                row["featureStepDeep"], row["featureIndexDeep"] = deep_entry
                pinned += 1
    data_file.write_text(json.dumps(structures, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{version}: pinned {pinned} structure rows")
