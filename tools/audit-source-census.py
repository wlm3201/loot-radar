"""独立复核结构源普查真值：直接解析存档的 .mca 区域文件，不启动 Minecraft 运行时。

手写 NBT 解析，校验区块是否为 FULL、陶罐纹样与物品清单；--plan-repair 可输出对账计划。
"""
import argparse
from collections import Counter
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zlib
from source_metrics import SOURCES


class NbtReader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def number(self, code):
        return struct.unpack('>' + code, self.stream.read(struct.calcsize('>' + code)))[0]

    def string(self):
        return self.stream.read(self.number('H')).decode('utf-8')

    def tag(self, kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
        if kind == 7:
            return self.stream.read(self.number('i'))
        if kind == 8:
            return self.string()
        if kind == 9:
            element = self.number('B')
            return [self.tag(element) for _ in range(self.number('i'))]
        if kind == 10:
            result = {}
            while (child := self.number('B')):
                key = self.string()
                result[key] = self.tag(child)
            return result
        if kind in (11, 12):
            return [self.number('i' if kind == 11 else 'q') for _ in range(self.number('i'))]
        raise ValueError(f'Unknown NBT tag {kind}')

    def root(self):
        kind = self.number('B')
        self.string()
        return self.tag(kind)


def region_chunks(path, allowed_chunks=None):
    parts = path.stem.split('.')
    region_x, region_z = int(parts[1]), int(parts[2])
    with path.open('rb') as file:
        offsets = file.read(4096)
        for index in range(1024):
            if allowed_chunks is not None and (region_x * 32 + (index & 31), region_z * 32 + (index >> 5)) not in allowed_chunks:
                continue
            location = int.from_bytes(offsets[index * 4:index * 4 + 4], 'big')
            if not location:
                continue
            file.seek((location >> 8) * 4096)
            length = int.from_bytes(file.read(4), 'big')
            compression = file.read(1)[0]
            if compression & 128:
                raise ValueError(f'External chunk stream not supported: {path}, slot {index}')
            data = file.read(length - 1)
            if compression == 1:
                data = gzip.decompress(data)
            elif compression == 2:
                data = zlib.decompress(data)
            elif compression != 3:
                raise ValueError(f'Unsupported compression {compression}: {path}')
            yield NbtReader(data).root()


def audit(path, plan_only=False):
    truth = json.loads(path.read_text('utf-8'))
    if not truth.get('complete') or truth.get('error'):
        raise ValueError(f'Incomplete census: {path}')
    dimension = truth['dimension'].split(':')[1]
    directory = path.parent / f'server/world/dimensions/minecraft/{dimension}/region'
    if not directory.is_dir():
        raise ValueError(f'No region directory: {directory}')
    expected = {tuple(row['pos']): row for row in truth['containers']}
    if len(expected) != len(truth['containers']):
        raise ValueError('Duplicate captured source positions')
    target_tables = {table for definition in SOURCES if definition.dimension == dimension
                     and definition.name not in {'刷怪房', '硫黄洞穴废弃矿井'} and not definition.dynamic for table in definition.tables}
    found, errors, unopened, omitted = set(), [], [], []
    chunks = 0
    selected_chunks = None
    if plan_only:
        selected_chunks = {(x, z) for start in truth['starts']
                           for x in range(start['box'][0] >> 4, (start['box'][3] >> 4)+1)
                           for z in range(start['box'][2] >> 4, (start['box'][5] >> 4)+1)}
        selected_chunks.update((pos[0] >> 4, pos[2] >> 4) for pos in expected)
    for region in sorted(directory.glob('*.mca')):
        for chunk in region_chunks(region, selected_chunks):
            chunks += 1
            for entity in chunk.get('block_entities', []):
                pos = (entity['x'], entity['y'], entity['z'])
                if pos[0]**2 + pos[2]**2 > truth['radius']**2:
                    continue
                if entity.get('LootTable') in target_tables:
                    unopened.append(dict(pos=pos, table=entity['LootTable']))
                if pos not in expected:
                    if entity.get('LootTable') in target_tables or entity.get('id') in {'minecraft:decorated_pot', 'minecraft:vault'}:
                        omitted.append(dict(pos=pos, table=entity.get('LootTable', ''), blockEntity=entity.get('id'), status=chunk.get('Status')))
                    continue
                found.add(pos)
                row = expected[pos]
                if chunk.get('Status') != 'minecraft:full':
                    errors.append(dict(pos=pos, error='captured source is not in a FULL chunk', status=chunk.get('Status')))
                if 'sherds' in row and Counter(row['sherds']) != Counter(entity.get('sherds', ['minecraft:brick']*4)):
                    errors.append(dict(pos=pos, error='saved pot decorations differ',
                                       savedSherds=entity.get('sherds'), capturedSherds=row['sherds']))
                items = Counter()
                if row['kind'] == 'vault':
                    continue
                else:
                    stacks = entity.get('Items', [])
                    if isinstance(entity.get('item'), dict):
                        stacks = [entity['item']]
                    for stack in stacks:
                        if stack.get('id') and stack.get('id') != 'minecraft:air':
                            items[stack['id']] += stack.get('count', stack.get('Count', 1))
                    if row['kind'] == 'pot_sherd':
                        items.update(entity.get('sherds', ['minecraft:brick']*4))
                if dict(items) != row['items']:
                    errors.append(dict(pos=pos, savedItems=dict(items), capturedItems=row['items'], kind=row['kind']))
    missing = sorted(expected.keys()-found)
    return dict(schema='loot-radar-source-region-audit/1', truth=str(path.resolve()), truthSHA256=hashlib.sha256(path.read_bytes()).hexdigest(),
                version=truth['version'], seed=truth['seed'], dimension=truth['dimension'], chunksRead=chunks,
                reportedSources=len(expected), savedSourcesFound=len(found), missingPositions=missing,
                unopenedTargetSources=unopened, omittedNativeSources=omitted, inventoryErrors=errors,
                auditScope='native_structure_footprints_and_reported_chunks' if plan_only else 'all_saved_chunks',
                passed=not missing and not unopened and not omitted and not errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('truth', type=Path)
    parser.add_argument('--plan-repair', action='store_true')
    args = parser.parse_args()
    result = audit(args.truth, args.plan_repair)
    target = args.truth.with_name('reconciliation-plan.json' if args.plan_repair else 'region-audit.json')
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({k: v for k, v in result.items() if not isinstance(v, list)}))
    print('missing', len(result['missingPositions']), 'unopened', len(result['unopenedTargetSources']), 'inventoryErrors', len(result['inventoryErrors']))
    raise SystemExit(0 if result['passed'] else 1)

if __name__ == '__main__':
    main()
