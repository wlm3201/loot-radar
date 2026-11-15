"""从 Minecraft client jar 导出物品图标到 web/public/icons/。

物品 id 列表来自 data/<version>/items.json；若没有独立物品贴图，
则回退到 textures/block/<id>.png。
"""
import argparse
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "web" / "public" / "icons"

OVERRIDES = {
}


def find_jar(minecraft_dir: Path, version: str) -> Path:
    return minecraft_dir / "versions" / version / f"{version}.jar"


def item_ids(version: str) -> list[str]:
    data = json.loads((ROOT / "data" / version / "items.json").read_text(encoding="utf-8"))
    return [item["id"] for item in data["items"]]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default=None, help="client jar 路径（优先于 --minecraft-dir）")
    parser.add_argument("--minecraft-dir", default=None, help=".minecraft 目录")
    parser.add_argument("--version", default="26.2")
    args = parser.parse_args()
    if args.jar:
        jar_path = Path(args.jar)
    elif args.minecraft_dir:
        jar_path = find_jar(Path(args.minecraft_dir), args.version)
    else:
        raise SystemExit("pass --jar <path-to-version-jar> or --minecraft-dir <path-to-.minecraft>")
    if not jar_path.is_file():
        raise SystemExit(f"client jar not found: {jar_path}")

    OUT.mkdir(parents=True, exist_ok=True)
    ids = item_ids(args.version)
    found, missing = 0, []
    with zipfile.ZipFile(jar_path) as jar:
        names = set(jar.namelist())
        for item_id in ids:
            path = item_id.split(":", 1)[1] if ":" in item_id else item_id
            texture = OVERRIDES.get(path, f"assets/minecraft/textures/item/{path}.png")
            if texture not in names:
                texture = f"assets/minecraft/textures/block/{path}.png"
            if texture not in names:
                missing.append(item_id)
                continue
            (OUT / f"{path}.png").write_bytes(jar.read(texture))
            found += 1
    print(f"icons: {found} written to {OUT}, {len(missing)} missing")
    for item_id in missing:
        print("  missing:", item_id)


if __name__ == "__main__":
    main()
