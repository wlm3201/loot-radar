"""把 26.2 的目录复制到 26.1.2 / 26.3 数据目录。

只复制 structures.json 与 items.json：loot_tables 覆盖表是版本专属的，
重跑本脚本时必须保留；后续再执行 extract-loot-table-diff.py。
"""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"


def relabel(folder: Path, version: str):
    for name in ("structures.json", "items.json"):
        path = folder / name
        data = json.loads(path.read_text(encoding="utf-8"))
        data["version"] = version
        path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )


def main():
    for version in ("26.1.2", "26.3"):
        target = DATA / version
        target.mkdir(parents=True, exist_ok=True)
        for name in ("structures.json", "items.json"):
            shutil.copy2(DATA / "26.2" / name, target / name)
        relabel(target, version)
    print("generated data/26.1.2 and data/26.3")


if __name__ == "__main__":
    main()
