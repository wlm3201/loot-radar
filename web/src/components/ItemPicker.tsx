import { useMemo, useState } from 'react'
import type { Catalog, ItemDef } from '../types'

// 物品多选：按 items.json 的 group 分组，默认选中项由 group.defaultSelected 决定。
interface Props {
  catalog: Catalog
  selected: string[]
  onChange: (ids: string[]) => void
}

export function ItemPicker({ catalog, selected, onChange }: Props) {
  const [query, setQuery] = useState('')
  const selectedSet = useMemo(() => new Set(selected), [selected])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return catalog.items.filter(
      (item) => q === '' || item.id.toLowerCase().includes(q) || item.zhName.includes(q),
    )
  }, [catalog.items, query])

  const byGroup = useMemo(() => {
    const map = new Map<string, ItemDef[]>()
    for (const item of filtered) {
      const list = map.get(item.group) ?? []
      list.push(item)
      map.set(item.group, list)
    }
    return map
  }, [filtered])

  const toggle = (id: string) => {
    onChange(selectedSet.has(id) ? selected.filter((x) => x !== id) : [...selected, id])
  }

  const toggleGroup = (groupId: string) => {
    const ids = (byGroup.get(groupId) ?? []).map((item) => item.id)
    if (ids.length === 0) return
    const allOn = ids.every((id) => selectedSet.has(id))
    if (allOn) {
      const drop = new Set(ids)
      onChange(selected.filter((id) => !drop.has(id)))
    } else {
      onChange([...new Set([...selected, ...ids])])
    }
  }

  return (
    <div className="panel p-3 flex flex-col gap-2 min-h-0 flex-1">
      <div className="flex items-center gap-2">
        <input
          type="text"
          placeholder="搜索物品（中文名或 ID）"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="flex-1"
        />
        <button type="button" className="text-xs text-bad" onClick={() => onChange([])}>
          清空选择
        </button>
      </div>
      <div className="flex flex-col gap-2 overflow-y-auto pr-1">
        {catalog.groups.map((group) => {
          const items = byGroup.get(group.id) ?? []
          if (items.length === 0) return null
          const groupSelected = items.filter((item) => selectedSet.has(item.id)).length
          return (
            <div key={group.id}>
              <div className="flex items-center justify-between mb-1">
                <button
                  type="button"
                  title="点击全选，再次点击全取消"
                  className={
                    'badge cursor-pointer select-none ' +
                    (groupSelected === 0
                      ? 'border-ink-600 text-[#64748B]'
                      : groupSelected >= items.length
                        ? 'border-accent bg-accent-dark/50 text-white'
                        : 'border-accent text-accent')
                  }
                  onClick={() => toggleGroup(group.id)}
                >
                  {group.zhName}（{groupSelected}/{items.length}）
                </button>
              </div>
              <div className="flex flex-wrap gap-1">
                {items.map((item) => {
                  const active = selectedSet.has(item.id)
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => toggle(item.id)}
                      title={item.id}
                      aria-pressed={active}
                      className={
                        active
                          ? 'text-xs px-2 py-0.5 border-accent bg-accent-dark/40 text-white font-semibold flex items-center gap-1'
                          : 'text-xs px-2 py-0.5 text-[#94A3B8] hover:text-white flex items-center gap-1'
                      }
                    >
                      <img
                        src={`/icons/${item.id.replace(/^minecraft:/, '')}.png`}
                        alt=""
                        className="w-4 h-4 pixelated"
                        onError={(e) => {
                          ;(e.target as HTMLImageElement).style.display = 'none'
                        }}
                      />
                      {item.zhName}
                    </button>
                  )
                })}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
