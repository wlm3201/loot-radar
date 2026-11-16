import { useI18n } from '../i18n'

// 结构过滤器：按显示名合并暖水/冷水海底废墟、沉船等同类结构，整组开关。
interface Props {
  candidates: string[]
  names: Record<string, string>
  excluded: Set<string>
  forced: Set<string>
  onToggle: (ids: string[]) => void
}

export function StructureFilter({ candidates, names, excluded, forced, onToggle }: Props) {
  const { t, structureName, structureFilterLabel } = useI18n()
  if (candidates.length === 0) return null
  const groups: { label: string; ids: string[] }[] = []
  const byLabel = new Map<string, { label: string; ids: string[] }>()
  for (const id of candidates) {
    const label = structureFilterLabel(structureName(id, names[id]))
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
      <span className="text-xs text-[#94A3B8]">{t('filter.title')}</span>
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
                  ? t('filter.lockedTip', { label: group.label })
                  : on
                    ? t('filter.excludeTip', { label: group.label })
                    : t('filter.includeTip', { label: group.label })
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
