"""通过 HTTP 测量搜索扫描缓存的冷/热耗时。

调用 /api/search 发起搜索并读取 SSE 直到 event: done；后端不在 8765 时设置 LR_PORT。
"""
import json
import os
import time
import urllib.request

API = f"http://127.0.0.1:{os.environ.get('LR_PORT', '8765')}"
ITEMS = ["minecraft:friend_pottery_sherd", "minecraft:mourner_pottery_sherd",
         "minecraft:guster_pottery_sherd", "minecraft:sheaf_pottery_sherd"]

def search(item_ids, radius):
    body = json.dumps({
        "seed": "4217525504911621938", "version": "26.2", "centerX": 0, "centerZ": 0,
        "radius": radius, "minCountPerItem": 1, "lootingLevel": 0, "startOffset": 1,
        "skipDungeon": False,
        "itemIds": item_ids, "itemBudgetSeconds": 300,
    }).encode()
    t0 = time.time()
    req = urllib.request.Request(API + "/api/search", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        sid = json.load(r)["searchId"]
    report = None
    with urllib.request.urlopen(f"{API}/api/search/{sid}/events", timeout=300) as r:
        event = None
        for raw in r:
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith("event:"):
                event = line.split(":", 1)[1].strip()
            elif line.startswith("data:") and event == "done":
                report = json.loads(line.split(":", 1)[1].strip())
    wall = time.time() - t0
    found = sum(report.get("foundPerItem", {}).values()) if report else -1
    structs = report.get("structuresScanned", -1) if report else -1
    containers = report.get("containersReplayed", -1) if report else -1
    return wall, structs, containers, found

def main():
    print("A  cold  r5000 :", search(ITEMS, 5000))
    print("A2 warm r5000 :", search(ITEMS, 5000), "  <- identical, expect far faster")
    print("B  warm+item r5000:", search(ITEMS + ["minecraft:brewer_pottery_sherd"], 5000),
          "  <- added item, expect fast")
    print("C  warm grow r9000:", search(ITEMS, 9000),
          "  <- grew radius, overlap cached")

if __name__ == "__main__":
    main()
