"""根据源普查的 truth.json + prediction.json 打印逐来源覆盖率表。

范围由真值文件里记录的结构起点推导；范围外的容器会在末尾单独列出，避免漏报被静默隐藏。
"""
import json
import sys

from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from source_metrics import SOURCES, key_of  # noqa: E402


def matches(row, definition):
    if row.get('_dim') != definition.dimension:
        return False
    if definition.method == '陶罐':
        return row.get('kind') == 'pot_sherd'
    return row.get('table') in definition.tables


def main():
    root = Path(sys.argv[1])
    dims = {}
    for truth_path in sorted(root.glob('*/truth.json')):
        dim = truth_path.parent.name
        pred_path = truth_path.parent / 'prediction.json'
        if pred_path.exists():
            dims[dim] = (json.loads(truth_path.read_text('utf-8')),
                         json.loads(pred_path.read_text('utf-8')))
    per = {}
    scan_times = {}
    cap = 100
    starts5000 = {}
    truth_containers = []
    pred_containers = []
    boxes_by_dim = {}
    for dim, (truth, prediction) in dims.items():
        cap = truth.get('perTypeCap', cap)
        per.update(truth.get('perStructure', {}))
        starts5000.update(truth.get('starts5000ByType', {}))
        boxes_by_dim[dim] = [{'structure': s['structure'], 'box': s['box']}
                             for s in truth.get('starts', [])]
        for name, seconds in prediction.get('scanSecondsByStructure', {}).items():
            scan_times[name] = scan_times.get(name, 0) + seconds
        truth_containers.extend(dict(r, _dim=dim) for r in truth['containers'])
        pred_containers.extend(dict(r, _dim=dim) for r in prediction['containers'])
        print(f"[{dim}] truth {len(truth['containers'])} sources / prediction "
              f"{len(prediction['containers'])} sources", file=sys.stderr)

    def in_scope(row, structures):
        x, _y, z = row['pos']
        for s in boxes_by_dim.get(row['_dim'], []):
            if s['structure'] in structures and \
                    s['box'][0] <= x <= s['box'][3] and s['box'][2] <= z <= s['box'][5]:
                return True
        return False

    first = next(iter(dims.values()))[0]
    print(f"== {first.get('version', 'unknown')} seed={first['seed']} r={first['radius']} cap={cap}/type ==")
    header = (f"{'来源':<22}{'总结构数(≤5k/全部)':>20}{'结构数':>7}{'生成区块':>9}"
              f"{'容器数':>7}{'耗时':>9}{'覆盖率':>9}{'准确率':>9}{'预测耗时':>9}")
    print(header)
    for definition in SOURCES:
        p_rows = [r for r in pred_containers if matches(r, definition)]
        scope_structures = {r['structure'] for r in p_rows if r.get('structure')}
        t_rows = [r for r in truth_containers
                  if matches(r, definition)
                  and (not scope_structures or in_scope(r, scope_structures))]
        t_keys = {key_of(r) for r in t_rows}
        p_keys = {key_of(r) for r in p_rows}
        matched = t_keys & p_keys

        structs = sorted({r.get('structure', '') for r in p_rows if r.get('structure')})
        total = sum(per.get(s, {}).get('totalStarts', 0) for s in structs) if structs else 0
        within = sum(starts5000.get(s, 0) for s in structs)
        used = sum(per.get(s, {}).get('usedStarts', 0) for s in structs)
        chunks = sum(per.get(s, {}).get('chunksGenerated', 0) for s in structs)
        seconds = sum(per.get(s, {}).get('seconds', 0) for s in structs)
        pred_seconds = sum(scan_times.get(s, 0) for s in definition.timing_sets)

        if definition.name in ('刷怪房', '硫黄洞穴废弃矿井'):
            coverage = accuracy = '范围外'
        elif definition.dynamic and definition.method in ('宝库', '生物'):
            coverage = accuracy = '交互验证'
        else:
            coverage = f"{100 * len(matched) / len(t_keys):.1f}%" if t_keys else '—'
            accuracy = f"{100 * len(matched) / len(p_keys):.1f}%" if p_keys else '—'
        counts = f"{within}/{total}" if structs else '—'
        print(f"{definition.name:<22}{counts:>20}{used or '—':>7}{chunks or '—':>9}"
              f"{len(t_keys):>7}{seconds:>8.2f}s{coverage:>9}{accuracy:>9}{pred_seconds:>8.2f}s")

    uncovered = []
    for table in sorted({r.get('table') or r.get('block', '?') for r in truth_containers}):
        if not any(table in d.tables or (d.method == '陶罐' and table == 'minecraft:decorated_pot')
                   for d in SOURCES):
            count = sum(1 for r in truth_containers
                        if (r.get('table') or r.get('block', '?')) == table)
            uncovered.append((table, count))
    if uncovered:
        print('\n不在覆盖表内的真值容器:')
        for table, count in uncovered:
            print(f'  {table}: {count}')


if __name__ == '__main__':
    main()
