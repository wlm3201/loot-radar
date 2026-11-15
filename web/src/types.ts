export function structureFilterLabel(name: string): string {
  return name.replace(/（[^（）]*）$/, '').replace(/^(暖水|冷水)/, '')
}

export interface ItemSource {
  label: string
  structures: string[]
  method: string
  pieces: string[]
  skipKey: string | null
}

export interface ItemDef {
  id: string
  zhName: string
  group: string
  sources: ItemSource[]
}

export interface ItemGroup {
  id: string
  zhName: string
  defaultSelected: boolean
}

export interface Catalog {
  groups: ItemGroup[]
  items: ItemDef[]
  structures: Record<string, string>
}

export interface SearchRequest {
  seed: string
  version: string
  centerX: number
  centerZ: number
  radius: number
  minCountPerItem: number
  lootingLevel: number
  startOffset: number
  skipDungeon: boolean
  itemIds: string[]
  itemBudgetSeconds: number
  structures: string[]
}

// 「已获取」标记键必须区分维度与种子，否则跨维度/跨种子的同坐标命中会互相串标记。
export function hitKey(hit: {
  itemId: string
  x: number
  y: number
  z: number
  dimension?: string
  worldSeed?: number
  nth: number
}): string {
  return `${hit.worldSeed ?? 0}|${hit.dimension ?? ''}|${hit.itemId}|${hit.x},${hit.y},${hit.z}|${hit.nth}`
}

export interface SearchHit {
  itemId: string
  itemZhName: string
  method: string
  x: number
  y: number
  z: number
  dimension: string
  structureId: string
  structureZhName: string
  count: number
  nth: number
  instanceIndex: number
  estimated: boolean
  elapsedNanos: number
  worldSeed: number
  workNanos: number
}

export interface SearchProgress {
  structuresScanned: number
  containersReplayed: number
  foundPerItem: Record<string, number>
  currentStructureId: string
  elapsedNanos: number
  elapsedNanosPerItem: Record<string, number>
  costNanosPerItem: Record<string, number>
  itemDurationNanos: Record<string, number>
  elapsedNanosPerStructure: Record<string, number>
}

export interface SearchReport {
  completed: boolean
  cancelled: boolean
  foundPerItem: Record<string, number>
  elapsedNanosPerItem: Record<string, number>
  costNanosPerItem: Record<string, number>
  elapsedNanosPerStructure: Record<string, number>
  totalElapsedNanos: number
  structuresScanned: number
  containersReplayed: number
  warnings: string[]
  itemDurationNanos: Record<string, number>
}

// 与 core/LootMethod.id 一一对应；后端 Gson 把枚举序列化为 id 字符串。
export const METHOD_LABELS: Record<string, string> = {
  chest: '箱子',
  archaeology: '考古',
  pot_sherd: '陶罐',
  corridor_pot: '柱廊陶罐',
  vault: '宝库',
  vault_ominous: '不祥宝库',
  entity: '远古守卫者',
}

export const DIMENSION_LABELS: Record<string, string> = {
  overworld: '主世界',
  the_nether: '下界',
  the_end: '末地',
}

export const SEQUENCE_LABELS: Record<string, string> = {
  entity: '远古守卫者',
  vault: '宝库',
  vault_ominous: '不祥宝库',
}

export function sourceLabel(hit: {
  method: string
  structureZhName: string
  structureId?: string
}): string {
  const merged = hit.structureId
    ? STRUCTURE_MERGE_LABELS[STRUCTURE_MERGES[hit.structureId] ?? hit.structureId]
    : undefined
  return SEQUENCE_LABELS[hit.method] ?? merged ?? hit.structureZhName
}

export function structureGroupKey(hit: SearchHit): string {
  if (SEQUENCE_LABELS[hit.method]) return `seq:${hit.method}`
  return STRUCTURE_MERGES[hit.structureId] ?? hit.structureId
}

const STRUCTURE_MERGES: Record<string, string> = {
  'minecraft:ocean_ruin_warm': 'minecraft:ocean_ruin',
  'minecraft:ocean_ruin_cold': 'minecraft:ocean_ruin',
  'minecraft:shipwreck': 'minecraft:shipwreck_all',
  'minecraft:shipwreck_beached': 'minecraft:shipwreck_all',
}

/** Display name for a merged structure group. */
export const STRUCTURE_MERGE_LABELS: Record<string, string> = {
  'minecraft:ocean_ruin': '海底废墟',
  'minecraft:shipwreck_all': '沉船',
}

export function structureGroupNanos(
  groupKey: string,
  times: Record<string, number>,
): number | undefined {
  const members = Object.entries(STRUCTURE_MERGES)
    .filter(([, merged]) => merged === groupKey)
    .map(([id]) => id)
  const keys = members.length > 0 ? members : [groupKey]
  let sum = 0
  let seen = false
  for (const key of keys) {
    const value = times[key]
    if (value !== undefined) {
      sum += value
      seen = true
    }
  }
  return seen ? sum : undefined
}

export function denseInstanceRanks(hits: SearchHit[]): Map<string, number> {
  const byGroup = new Map<string, Set<string>>()
  for (const hit of hits) {
    if (hit.nth >= 1) continue
    const group = structureGroupKey(hit)
    const set = byGroup.get(group) ?? new Set<string>()
    set.add(`${hit.structureId}#${hit.instanceIndex}`)
    byGroup.set(group, set)
  }
  const ranks = new Map<string, number>()
  for (const set of byGroup.values()) {
    let rank = 0
    for (const key of set) {
      ranks.set(key, ++rank)
    }
  }
  return ranks
}

export function mergeSameLocation(hits: SearchHit[]): SearchHit[] {
  const map = new Map<string, SearchHit>()
  for (const hit of hits) {
    // structureId 参与去重：同一坐标上不同结构的容器不应合并成一条。
    const key = `${hit.itemId}|${hit.x},${hit.y},${hit.z}|${hit.dimension}|${hit.nth}|${hit.structureId}`
    const prev = map.get(key)
    if (prev) {
      map.set(key, {
        ...prev,
        count: prev.count + hit.count,
        elapsedNanos: Math.min(prev.elapsedNanos, hit.elapsedNanos),
      })
    } else {
      map.set(key, { ...hit })
    }
  }
  return [...map.values()]
}
