"""把 Bounce 普查的 truth.json / prediction.json 配对统计成覆盖率 JSON + CSV。

case 目录从输入根自动发现（命名 <version>_<seed>），无需硬编码版本与种子；
可用 --versions / --seeds 缩小范围。统计单位是「含至少一张 Bounce 的箱子矿车位置」。
"""
import argparse
import csv
import json
from pathlib import Path


def index_carts(rows):
    result = {}
    for row in rows:
        key = tuple(row['pos'])
        if key in result:
            raise ValueError(f'Duplicate container position: {key}')
        result[key] = row
    return result


def metrics(actual, predicted):
    a = {key: row for key, row in actual.items() if row['bounceCount'] > 0}
    p = {key: row for key, row in predicted.items() if row['bounceCount'] > 0}
    common = a.keys() & p.keys()
    return {
        'actual': len(a), 'predicted': len(p), 'truePositive': len(common),
        'falseNegative': len(a.keys() - p.keys()), 'falsePositive': len(p.keys() - a.keys()),
        'recallPercent': 100 * len(common) / len(a) if a else None,
        'precisionPercent': 100 * len(common) / len(p) if p else None,
        'actualDiscs': sum(row['bounceCount'] for row in a.values()),
        'predictedDiscs': sum(row['bounceCount'] for row in p.values()),
        'matchingLootSeed': sum(a[key]['lootSeed'] == p[key]['lootSeed'] for key in common),
        'matchingDiscCount': sum(a[key]['bounceCount'] == p[key]['bounceCount'] for key in common),
    }


def compare(case):
    truth = json.loads((case / 'truth.json').read_text('utf-8'))
    if not truth['complete'] or truth.get('error'):
        raise ValueError(f'Incomplete truth: {case}')
    absent = truth['phase'] == 'unavailable_in_version'
    prediction = None if absent else json.loads((case / 'prediction.json').read_text('utf-8'))
    if prediction is not None:
        if not prediction['complete'] or prediction.get('error'):
            raise ValueError(f'Incomplete prediction: {case}')
        for key in ('version', 'seed', 'center', 'radius'):
            if truth[key] != prediction[key]:
                raise ValueError(f'Mismatched {key}: {case}')
    actual = index_carts(truth['observedCarts'])
    expanded = index_carts(prediction['predictedCarts']) if prediction else {}
    predicted = {key: row for key, row in expanded.items() if row['withinSearchRootRadius']}
    score = metrics(actual, predicted)
    a = {key: row for key, row in actual.items() if row['bounceCount'] > 0}
    p = {key: row for key, row in predicted.items() if row['bounceCount'] > 0}
    details = {
        'truePositive': [{'actual': a[key], 'prediction': p[key]} for key in sorted(a.keys() & p.keys())],
        'falseNegative': [{'actual': a[key], 'predictionAtPosition': predicted.get(key)} for key in sorted(a.keys() - p.keys())],
        'falsePositive': [{'prediction': p[key], 'observedActualAtPosition': actual.get(key)} for key in sorted(p.keys() - a.keys())],
    }
    return {
        'version': truth['version'], 'seed': truth['seed'], 'center': truth['center'], 'radius': truth['radius'],
        'status': 'item_absent_in_version' if absent else 'complete', **score,
        'expandedRootMetrics': metrics(actual, expanded),
        'truthSeconds': truth['elapsedSeconds'],
        'predictionSeconds': prediction['elapsedSeconds'] if prediction else None,
        'truthTargetChunks': truth['targetChunks'],
        'truthStartsChecked': truth['startsChecked'],
        'predictionTerrain': prediction['terrain'] if prediction else None,
        'details': details,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--versions', nargs='+', default=None)
    parser.add_argument('--seeds', nargs='+', default=None)
    args = parser.parse_args()
    # 目录形如 <version>_<seed>；只处理带 truth.json 的目录，缺失的 case 直接跳过。
    case_dirs = sorted(
        path for path in args.input.iterdir()
        if path.is_dir() and (path / 'truth.json').is_file()
    )
    if args.versions:
        case_dirs = [p for p in case_dirs if p.name.split('_', 1)[0] in args.versions]
    if args.seeds:
        case_dirs = [p for p in case_dirs if '_' in p.name and p.name.split('_', 1)[1] in args.seeds]
    if not case_dirs:
        raise SystemExit(f'no truth.json cases found under {args.input}')
    cases = [compare(path) for path in case_dirs]
    aggregates = []
    for version in sorted({row['version'] for row in cases}):
        rows = [row for row in cases if row['version'] == version and row['status'] == 'complete']
        if not rows:
            continue
        total = {key: sum(row[key] for row in rows) for key in (
            'actual', 'predicted', 'truePositive', 'falseNegative', 'falsePositive',
            'actualDiscs', 'predictedDiscs', 'matchingLootSeed', 'matchingDiscCount')}
        total.update(version=version,
                     recallPercent=100 * total['truePositive'] / total['actual'] if total['actual'] else None,
                     precisionPercent=100 * total['truePositive'] / total['predicted'] if total['predicted'] else None)
        aggregates.append(total)
    result = {'schema': 'loot-radar-bounce-coverage/1',
              'unit': 'unique chest-minecart XYZ containing at least one Bounce',
              'radiusMode': 'horizontal circle; center (0,0); radius 5000 blocks',
              'searchMode': 'exhaustive, no hit quota or time budget; original structure-root radius filter',
              'zeroDenominator': 'null / N/A, never 100%',
              'cases': cases, 'aggregate': aggregates}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'bounce-coverage.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
    fields = ['version', 'seed', 'status', 'actual', 'predicted', 'truePositive', 'falseNegative', 'falsePositive',
              'recallPercent', 'precisionPercent', 'actualDiscs', 'predictedDiscs', 'matchingLootSeed', 'matchingDiscCount']
    with (args.output / 'bounce-coverage.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=fields, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(cases)
    for row in cases + aggregates:
        print(json.dumps({key: value for key, value in row.items() if key not in ('details', 'expandedRootMetrics')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
