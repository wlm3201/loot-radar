import { structureFilterLabel } from '../types'

// 结构过滤器：按中文名合并暖水/冷水海底废墟、沉船等同类结构，整组开关。
interface Props {
  candidates: string[]
  names: Record<string, string>
  excluded: Set<string>
  forced: Set<string>
  onToggle: (ids: string[]) => void
}

export function StructureFilter({ candidates, names, excluded, forced, onToggle }: Props) {
  if (candidates.length === 0) return null
  const groups: { label: string; ids: string[] }[] = []
  const byLabel = new Map<string, { label: string; ids: string[] }>()
  for (const id of candidates) {
    const label = structureFilterLabel(names[id] ?? id)
    let group = byLabel.get(label)
    if (!group) {
      group = { label, ids: [] }
      byLabel.set(label, group)
      groups.push(group)
    }
    group.ids.push(id)
  }
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-[#94A3B8]">候选结构</span>
      <div className="flex flex-wrap gap-1">
        {groups.map((group) => {
          const on = group.ids.some((id) => !excluded.has(id))
          const locked = on && group.ids.some((id) => forced.has(id))
          return (
            <button
              key={group.label}
              type="button"
              disabled={locked}
              onClick={() => onToggle(group.ids)}
              title={
                locked
                  ? `${group.label}：所选物品中有仅剩此来源的，不可取消`
                  : on
                    ? `点击取消搜索${group.label}`
                    : `点击恢复搜索${group.label}`
              }
              className={
                'px-2 py-0.5 rounded text-xs border ' +
                (on
                  ? 'bg-ink-700 border-ink-500'
                  : 'bg-ink-900 border-ink-700 text-[#64748B] line-through') +
                (locked ? ' cursor-not-allowed opacity-90' : '')
              }
            >
              {locked ? '🔒 ' : ''}
              {group.label}
            </button>
          )
        })}
      </div>
    </div>
  )
}
