import { useMemo, useState } from 'react'
import type { ItemDef, SearchRequest } from '../types'
import { structureFilterLabel } from '../types'
import { StructureFilter } from './StructureFilter'

// 搜索参数表单。structures 是「候选结构 - 用户排除」的白名单，交给后端做来源过滤。
interface Props {
  versions: string[]
  version: string
  onVersionChange: (version: string) => void
  running: boolean
  onSearch: (request: SearchRequest) => void
  onCancel: () => void
  itemIds: string[]
  items: ItemDef[]
  structureNames: Record<string, string>
}

export function SearchForm({
  versions,
  version,
  onVersionChange,
  running,
  onSearch,
  onCancel,
  itemIds,
  items,
  structureNames,
}: Props) {
  const [seed, setSeed] = useState('0')
  const [centerX, setCenterX] = useState('0')
  const [centerZ, setCenterZ] = useState('0')
  const [radius, setRadius] = useState('5000')
  const [minCount, setMinCount] = useState('3')
  const [looting, setLooting] = useState('3')
  const [startOffset, setStartOffset] = useState('1')
  const [excludedStructures, setExcludedStructures] = useState<Set<string>>(
    () =>
      new Set([
        'minecraft:monster_room',
        'minecraft:village_plains',
        'minecraft:village_desert',
        'minecraft:village_savanna',
        'minecraft:village_snowy',
        'minecraft:village_taiga',
      ]),
  )
  const [itemBudget, setItemBudget] = useState('60')
  const normalizedSeed = seed.trim()
  const seedValid =
    /^[+-]?\d+$/.test(normalizedSeed) &&
    BigInt(normalizedSeed) >= -(1n << 63n) &&
    BigInt(normalizedSeed) < 1n << 63n

  // candidates 来自所选物品的所有来源；forced 是「某物品仅剩的唯一来源」，不允许取消。
  const byId = useMemo(() => new Map(items.map((item) => [item.id, item])), [items])
  const candidates = useMemo(() => {
    const out: string[] = []
    const seen = new Set<string>()
    for (const id of itemIds) {
      for (const source of byId.get(id)?.sources ?? []) {
        for (const structure of source.structures) {
          if (!seen.has(structure)) {
            seen.add(structure)
            out.push(structure)
          }
        }
      }
    }
    return out
  }, [byId, itemIds])
  const forced = useMemo(() => {
    const out = new Set<string>()
    for (const id of itemIds) {
      const groups = new Map<string, string[]>()
      for (const source of byId.get(id)?.sources ?? []) {
        for (const structure of source.structures) {
          if (excludedStructures.has(structure)) continue
          const label = structureFilterLabel(structureNames[structure] ?? structure)
          const members = groups.get(label) ?? []
          members.push(structure)
          groups.set(label, members)
        }
      }
      if (groups.size === 1) {
        for (const members of groups.values()) {
          for (const structure of members) out.add(structure)
        }
      }
    }
    return out
  }, [byId, itemIds, excludedStructures, structureNames])
  const toggleStructure = (ids: string[]) => {
    setExcludedStructures((current) => {
      const next = new Set(current)
      if (ids.every((id) => next.has(id))) {
        for (const id of ids) next.delete(id)
      } else {
        if (ids.some((id) => forced.has(id))) return current
        for (const id of ids) next.add(id)
      }
      return next
    })
  }
  const intValid = (v: string, min: number, max: number) => {
    const n = Number(v)
    return Number.isFinite(n) && Number.isInteger(n) && n >= min && n <= max
  }
  const fieldsValid =
    intValid(centerX, -30_000_000, 30_000_000) &&
    intValid(centerZ, -30_000_000, 30_000_000) &&
    intValid(radius, 1, 500_000) &&
    intValid(minCount, 1, 9999) &&
    intValid(startOffset, 1, 100_000) &&
    intValid(itemBudget, 0, 86_400)

  const submit = () => {
    onSearch({
      seed: normalizedSeed,
      version,
      centerX: Number(centerX),
      centerZ: Number(centerZ),
      radius: Number(radius),
      minCountPerItem: Number(minCount),
      lootingLevel: Number(looting),
      startOffset: Number(startOffset),
      skipDungeon: false,
      itemIds,
      itemBudgetSeconds: Math.max(0, Number(itemBudget) || 0),
      structures: candidates.filter((structure) => !excludedStructures.has(structure)),
    })
  }

  return (
    <div className="panel p-3 flex flex-col gap-3 flex-1">
      <div className="grid grid-cols-2 gap-2">
        <label className="flex flex-col gap-1 col-span-2">
          <span className="text-xs text-[#94A3B8]">世界种子</span>
          <input type="text" value={seed} onChange={(e) => setSeed(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">中心 X</span>
          <input type="number" value={centerX} onChange={(e) => setCenterX(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">中心 Z</span>
          <input type="number" value={centerZ} onChange={(e) => setCenterZ(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">游戏版本</span>
          <select
            value={version}
            onChange={(e) => onVersionChange(e.target.value)}
            className="bg-ink-800 border border-ink-600 rounded px-2 py-1"
          >
            {versions.map((v) => (
              <option key={v} value={v}>
                {v}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">每物品数量</span>
          <input type="number" value={minCount} onChange={(e) => setMinCount(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">初始宝库/击杀次数</span>
          <input
            type="number"
            value={startOffset}
            onChange={(e) => setStartOffset(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">抢夺等级</span>
          <select
            value={looting}
            onChange={(e) => setLooting(e.target.value)}
            className="bg-ink-800 border border-ink-600 rounded px-2 py-1"
          >
            <option value="0">0</option>
            <option value="1">1</option>
            <option value="2">2</option>
            <option value="3">3</option>
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">最大范围</span>
          <input type="number" value={radius} onChange={(e) => setRadius(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-xs text-[#94A3B8]">每物品超时</span>
          <input
            type="number"
            value={itemBudget}
            onChange={(e) => setItemBudget(e.target.value)}
            title="单个物品的累计搜索时间上限"
          />
        </label>
      </div>

      {!seedValid && <span className="text-warn text-xs">请输入 64 位有符号整数种子</span>}
      {!fieldsValid && (
        <span className="text-warn text-xs">
          参数需为整数：范围 1~500000、数量 1~9999、中心 ±30000000、起始 1~100000、超时 0~86400
        </span>
      )}

      <StructureFilter
        candidates={candidates}
        names={structureNames}
        excluded={excludedStructures}
        forced={forced}
        onToggle={toggleStructure}
      />

      <div className="flex gap-2">
        <button
          type="button"
          onClick={submit}
          disabled={running || itemIds.length === 0 || !seedValid || !fieldsValid}
          className="bg-accent-dark border-accent-deep font-semibold flex-1"
        >
          {running ? '搜索中…' : `开始搜索（${itemIds.length} 项）`}
        </button>
        {running && (
          <button type="button" onClick={onCancel} className="border-bad text-bad">
            停止
          </button>
        )}
      </div>
    </div>
  )
}
