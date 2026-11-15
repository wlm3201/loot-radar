"""打印刷怪房（chests/simple_dungeon）的覆盖率/准确率表。

口径复用 source_metrics.py 的 SOURCES；矿井与刷怪房不在默认普查范围内，单独统计。
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from source_metrics import key_of  # noqa: E402

truth = json.loads(Path(sys.argv[1]).read_text('utf-8'))
pred = json.loads(Path(sys.argv[2]).read_text('utf-8'))


t_rows = [r for r in truth['containers'] if r.get('table') == 'minecraft:chests/simple_dungeon']
p_rows = pred['containers']
t_keys = {key_of(r) for r in t_rows}
p_keys = {key_of(r) for r in p_rows}
matched = t_keys & p_keys
print(f"truth rows={len(t_rows)} ({len(t_keys)} unique keys)")
print(f"pred  rows={len(p_rows)} ({len(p_keys)} unique keys)")
print(f"matched={len(matched)} coverage={100 * len(matched) / len(t_keys):.1f}% "
      f"accuracy={100 * len(matched) / len(p_keys) if p_keys else 0:.1f}%")
print(f"chunks scanned={pred['chunksScanned']} scanSeconds={pred['scanSeconds']:.2f}")
