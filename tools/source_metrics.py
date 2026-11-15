"""源普查脚本共用的口径定义与对比逻辑。

SOURCES 是「结构名 / 维度 / 获取方式」的业务清单；key_of 把真值或预测容器
归一成 (x, z, 排序后的物品计数)，并排除无法仅凭 id 比较的 NBT 物品。
"""
from collections import Counter
from dataclasses import dataclass


@dataclass(frozen=True)
class Source:
    name: str
    dimension: str
    method: str
    tables: tuple[str, ...]
    timing_sets: tuple[str, ...]
    dynamic: bool = False


def source(name, dimension, method, tables, timing, dynamic=False):
    return Source(name, dimension, method, tuple('minecraft:' + x for x in tables),
                  tuple('minecraft:' + x for x in timing), dynamic)


SOURCES = [
    source('堡垒遗迹藏宝室', 'the_nether', '箱子', ['chests/bastion_treasure'], ['nether_complexes']),
    source('堡垒遗迹桥', 'the_nether', '箱子', ['chests/bastion_bridge'], ['nether_complexes']),
    source('堡垒遗迹塔尖', 'the_nether', '箱子', ['chests/bastion_other'], ['nether_complexes']),
    source('堡垒遗迹疣猪兽棚', 'the_nether', '箱子', ['chests/bastion_hoglin_stable'], ['nether_complexes']),
    source('沉船宝箱', 'overworld', '箱子', ['chests/shipwreck_treasure'], ['shipwrecks']),
    source('沉船补给箱', 'overworld', '箱子', ['chests/shipwreck_supply'], ['shipwrecks']),
    source('沉船地图箱', 'overworld', '箱子', ['chests/shipwreck_map'], ['shipwrecks']),
    source('丛林神庙', 'overworld', '箱子', ['chests/jungle_temple'], ['jungle_temples']),
    source('村庄武器匠小屋', 'overworld', '箱子', ['chests/village/village_weaponsmith'], ['villages']),
    source('大型海底废墟', 'overworld', '箱子', ['chests/underwater_ruin_big'], ['ocean_ruins']),
    source('废弃传送门', 'overworld', '箱子', ['chests/ruined_portal'], ['ruined_portals']),
    source('废弃传送门', 'the_nether', '箱子', ['chests/ruined_portal'], ['ruined_portals']),
    source('古迹废墟', 'overworld', '考古', ['archaeology/trail_ruins_common', 'archaeology/trail_ruins_rare'], ['trail_ruins']),
    source('冷水海底废墟', 'overworld', '考古', ['archaeology/ocean_ruin_cold'], ['ocean_ruins']),
    source('林地府邸', 'overworld', '箱子', ['chests/woodland_mansion'], ['woodland_mansions']),
    source('硫黄洞穴废弃矿井', 'overworld', '箱子', ['chests/abandoned_mineshaft'], ['mineshafts']),
    source('掠夺者前哨站', 'overworld', '箱子', ['chests/pillager_outpost'], ['pillager_outposts']),
    source('埋藏的宝藏', 'overworld', '箱子', ['chests/buried_treasure'], ['buried_treasures']),
    source('末地城', 'the_end', '箱子', ['chests/end_city_treasure'], ['end_cities']),
    source('暖水海底废墟', 'overworld', '考古', ['archaeology/ocean_ruin_warm'], ['ocean_ruins']),
    source('沙漠神殿', 'overworld', '考古', ['archaeology/desert_pyramid'], ['desert_pyramids']),
    source('沙漠神殿', 'overworld', '箱子', ['chests/desert_pyramid'], ['desert_pyramids']),
    source('沙漠水井', 'overworld', '考古', ['archaeology/desert_well'], ['desert_well']),
    source('试炼密室宝库', 'overworld', '宝库', ['chests/trial_chambers/reward'], ['trial_chambers'], True),
    source('试炼密室不祥宝库', 'overworld', '宝库', ['chests/trial_chambers/reward_ominous'], ['trial_chambers'], True),
    source('试炼密室陶罐', 'overworld', '陶罐', [], ['trial_chambers']),
    source('试炼密室柱廊陶罐', 'overworld', '箱子', ['pots/trial_chambers/corridor'], ['trial_chambers']),
    source('刷怪房', 'overworld', '箱子', ['chests/simple_dungeon'], ['monster_room']),
    source('下界要塞', 'the_nether', '箱子', ['chests/nether_bridge'], ['nether_complexes']),
    source('小型海底废墟', 'overworld', '箱子', ['chests/underwater_ruin_small'], ['ocean_ruins']),
    source('要塞图书馆', 'overworld', '箱子', ['chests/stronghold_library'], ['strongholds']),
    source('要塞走廊', 'overworld', '箱子', ['chests/stronghold_corridor', 'chests/stronghold_crossing'], ['strongholds']),
    source('远古城市', 'overworld', '箱子', ['chests/ancient_city'], ['ancient_cities']),
    source('远古守卫者', 'overworld', '生物', [], ['ocean_monuments'], True),
    source('雪屋', 'overworld', '箱子', ['chests/igloo_chest'], ['igloos']),
]


def targets_for(definition, catalog):
    method = {'箱子': {'chest', 'corridor_pot'}, '考古': {'archaeology'}, '陶罐': {'pot_sherd'},
              '宝库': {'vault', 'vault_ominous'}, '生物': {'entity'}}[definition.method]
    targets = set()
    for item in catalog['items']:
        for origin in item['sources']:
            if origin['method'] not in method:
                continue
            # 有的目录把 pieces 写成更细的片段名（如「雪屋地下室」），此时仍需匹配
            # source 的整体名称；否则该口径会找不到任何目标物品。
            labels = [label.removesuffix('（运输矿车）')
                      for label in list(origin.get('pieces', [])) + [origin.get('label', '')]]
            if definition.name in labels:
                targets.add(item['id'])
    return targets


NBT_ITEMS = {'minecraft:map', 'minecraft:filled_map', 'minecraft:buried_treasure_map',
             'minecraft:enchanted_book', 'minecraft:written_book', 'minecraft:knowledge_book'}


def key_of(row):
    if row.get('kind') == 'pot_sherd' and 'sherds' in row:
        items = dict(Counter(row['sherds']))
    else:
        items = item_counts(row)
    items = {k: v for k, v in items.items() if k not in NBT_ITEMS}
    x, _y, z = row['pos']
    return (x, z, tuple(sorted(items.items())))


def item_counts(row):
    items = row.get('items', {})
    if isinstance(items, dict):
        return {item: count for item, count in items.items() if count > 0}
    result = {}
    for stack in items:
        if stack['count'] > 0:
            result[stack['item']] = result.get(stack['item'], 0) + stack['count']
    return result


def select(rows, definition, targets):
    result = {}
    for row in rows:
        if definition.method == '陶罐':
            if 'sherds' in row:
                items = set(row['sherds'])
            elif row.get('kind') == 'pot_sherd':
                items = set(item_counts(row))
            else:
                continue
        else:
            if row.get('table') not in definition.tables:
                continue
            items = set(item_counts(row))
        matched = items & targets
        if matched:
            result.setdefault(tuple(row['pos']), set()).update(matched)
    return result


def compare_sources(actual_rows, predicted_rows, definition, targets, *, precision_only=False):
    actual = select(actual_rows, definition, targets)
    predicted = select(predicted_rows, definition, targets)
    matched = {key for key in actual.keys() & predicted.keys() if actual[key] & predicted[key]}
    return dict(actual=None if precision_only else len(actual), predicted=len(predicted),
                truePositive=len(matched), falseNegative=None if precision_only else len(actual)-len(matched),
                falsePositive=len(predicted)-len(matched),
                recallPercent=None if precision_only or not actual else 100*len(matched)/len(actual),
                precisionPercent=100*len(matched)/len(predicted) if predicted else None,
                falsePositivePositions=[list(k) for k in sorted(predicted.keys()-matched)],
                falseNegativePositions=None if precision_only else [list(k) for k in sorted(actual.keys()-matched)],
                unit='unique dimension+XYZ within one version/seed/source; at least one correctly predicted target item')
