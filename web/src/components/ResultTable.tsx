import { useMemo, useState } from 'react'
import type { SearchHit } from '../types'
import {
  METHOD_ORDER,
  denseInstanceRanks,
  hitKey,
  mergeSameLocation,
  structureGroupKey,
  structureGroupNanos,
} from '../types'
import { useI18n } from '../i18n'

// 结果表：按物品或结构分组，合并同坐标命中，并展示配额、耗时与「第 N 次」序列信息。
interface Props {
  hits: SearchHit[]
  mode: 'item' | 'structure'
  itemIds: string[]
  itemNames: Map<string, string>
  itemDurationNanos: Record<string, number>
  costNanosPerItem: Record<string, number>
  elapsedNanosPerStructure: Record<string, number>
  foundPerItem: Record<string, number>
  quota: number
  running: boolean
  marked: Set<string>
  onToggleMark: (key: string) => void
  centerX: number
  centerZ: number
}

export function formatSeconds(nanos: number | undefined): string {
  if (nanos === undefined || nanos < 0) return '—'
  return `${(nanos / 1_000_000_000).toFixed(2)}s`
}

export function itemIconPath(itemId: string): string {
  return `/icons/${itemId.replace(/^minecraft:/, '')}.png`
}

const ITEM_GRID =
  'grid grid-cols-[1.25rem_minmax(5.5rem,18rem)_2.25rem_2.75rem_minmax(4rem,6.5rem)_3.25rem_2.5rem] gap-x-1 justify-start items-center'

const HEADER_BTN =
  'w-full text-left px-0 py-0 border-0 rounded-none shadow-none hover:bg-ink-700 hover:border-transparent active:bg-ink-700'

function MethodBadges({ methods }: { methods: string[] }) {
  const { methodLabel } = useI18n()
  const order: readonly string[] = METHOD_ORDER
  // 未知 method 的 indexOf 为 -1，必须排到已知标签之后，否则会跑到最前面。
  const rank = (method: string) => {
    const index = order.indexOf(method)
    return index < 0 ? order.length : index
  }
  const labels = [
    ...new Set(
      methods
        .sort((a, b) => rank(a) - rank(b))
        .map((m) => methodLabel(m)),
    ),
  ]
  return (
    <span className="badge border-info/60 text-info whitespace-nowrap shrink-0 px-1">
      {labels.join('·')}
    </span>
  )
}

function CoordsCell({ hit }: { hit: SearchHit }) {
  const { t } = useI18n()
  const [flash, setFlash] = useState<string | null>(null)
  const coords = `${hit.x} ${hit.y} ${hit.z}`
  const copy = (text: string, label: string) => {
    void navigator.clipboard.writeText(text)
    setFlash(label)
    window.setTimeout(() => setFlash(null), 900)
  }
  // 宝库/生物序列在扫到真实锚点前坐标退化为搜索中心，展示出来会误导为「0 64 0 0格」。
  if (hit.estimated) {
    return (
      <span
        className="font-mono text-right text-[#64748B] truncate"
      >
        —
      </span>
    )
  }
  return (
    <span
      className="font-mono cursor-pointer hover:text-white text-[#94A3B8] truncate text-right"
      title={t('results.copyTip')}
      onClick={() => copy(coords, t('results.copied'))}
      onContextMenu={(e) => {
        e.preventDefault()
        copy(`/tp ${coords}`, t('results.copiedTp'))
      }}
    >
      {flash ?? coords}
    </span>
  )
}

function HitRow({
  hit,
  mode,
  marked,
  onToggleMark,
  centerX,
  centerZ,
  rank,
}: {
  hit: SearchHit
  mode: 'item' | 'structure'
  marked: Set<string>
  onToggleMark: (key: string) => void
  centerX: number
  centerZ: number
  rank?: number
}) {
  const { t, itemName, dimensionLabel, sourceLabel } = useI18n()
  const key = hitKey(hit)
  const isMarked = marked.has(key)
  return (
    <div
      className={`${ITEM_GRID} pl-1 pr-1 py-1.5 border-t border-ink-700 text-xs ${isMarked ? 'opacity-45' : ''}`}
    >
      <span />
      <span className="flex items-center gap-1.5 min-w-0">
        {mode === 'structure' && (
          <img
            src={itemIconPath(hit.itemId)}
            alt=""
            className="w-4 h-4 pixelated shrink-0"
            onError={(e) => {
              ;(e.target as HTMLImageElement).style.display = 'none'
            }}
          />
        )}
        <span className="text-[#94A3B8] truncate">
          {mode === 'item'
            ? `${dimensionLabel(hit.dimension)} · ${sourceLabel(hit)}`
            : itemName(hit.itemId, hit.itemZhName)}
        </span>
        {mode === 'structure' && rank !== undefined && rank > 0 && (
          <span className="text-accent shrink-0" title={t('results.instanceRankTip')}>
            #{rank}
          </span>
        )}
        {hit.nth >= 0 && (
          <span className="shrink-0 text-[#94A3B8]" title={t('results.nthTip')}>
            {t('results.nth', { count: hit.nth })}
          </span>
        )}
      </span>
      <span className="text-good text-right">×{hit.count}</span>
      <span
        className="text-[#94A3B8] text-right"
      >
        {formatSeconds(hit.workNanos)}
      </span>
      <CoordsCell hit={hit} />
      <span
        className="text-right text-[#64748B]"
      >
        {hit.estimated
          ? '—'
          : t('results.distance', {
              count: Math.round(Math.hypot(hit.x - centerX, hit.z - centerZ)),
            })}
      </span>
      <button
        type="button"
        title={isMarked ? t('results.unmark') : t('results.mark')}
        onClick={() => onToggleMark(key)}
        className={
          'justify-self-start px-1 py-0.5 text-xs ' +
          (isMarked
            ? 'border-good text-good bg-good/10'
            : 'border-ink-600 text-[#64748B] hover:text-white')
        }
      >
        {isMarked ? '✔' : '□'}
      </button>
    </div>
  )
}

export function ResultTable({
  hits,
  mode,
  itemIds,
  itemNames,
  itemDurationNanos,
  elapsedNanosPerStructure,
  foundPerItem,
  quota,
  running,
  marked,
  onToggleMark,
  centerX,
  centerZ,
}: Props) {
  const { t, itemName, sourceLabel } = useI18n()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const ranks = useMemo(() => denseInstanceRanks(hits), [hits])

  // 物品模式按 itemId 建组（先把请求里的物品放进去，保证未命中的也显示一行），
  // 结构模式按合并后的结构组键建组；组内再合并同坐标命中。
  const groups = useMemo(() => {
    const map = new Map<string, SearchHit[]>()
    const push = (key: string, hit: SearchHit) => {
      const list = map.get(key)
      if (list) {
        list.push(hit)
      } else {
        map.set(key, [hit])
      }
    }
    if (mode === 'item') {
      for (const id of itemIds) {
        map.set(id, [])
      }
      for (const hit of hits) {
        push(hit.itemId, hit)
      }
    } else {
      for (const hit of hits) {
        push(structureGroupKey(hit), hit)
      }
    }
    for (const [key, list] of map) {
      map.set(key, mergeSameLocation(list))
    }
    return map
  }, [hits, mode, itemIds])

  const toggle = (key: string) => {
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  return (
    <div className="panel p-2 h-full flex flex-col min-h-0">
      <div className="flex items-center gap-2 pb-1.5 text-xs shrink-0">
        <span className="text-[#94A3B8]">
          {mode === 'item'
            ? t('results.itemsCount', { count: groups.size })
            : t('results.structuresCount', { count: groups.size })}
        </span>
        <button
          type="button"
          className="ml-auto px-2 py-0.5"
          onClick={() => setExpanded(new Set(groups.keys()))}
        >
          {t('results.expandAll')}
        </button>
        <button type="button" className="px-2 py-0.5" onClick={() => setExpanded(new Set())}>
          {t('results.collapseAll')}
        </button>
      </div>
      <div className="overflow-y-auto overflow-x-hidden min-h-0">
        {groups.size === 0 && (
          <div className="text-[#94A3B8] text-sm p-4">{t('results.empty')}</div>
        )}
        {[...groups.entries()].map(([key, groupHits]) => {
          const first = groupHits[0]
          const open = expanded.has(key)
          const isItem = mode === 'item'
          const title = isItem
            ? (itemNames.get(key) ??
              (first ? itemName(first.itemId, first.itemZhName) : key))
            : sourceLabel(first ?? { method: 'chest', structureZhName: key, structureId: key })
          const subtitle = isItem
            ? ''
            : [...new Set(groupHits.map((h) => itemName(h.itemId, h.itemZhName)))].join(
                t('results.subtitleJoin'),
              )
          const total = groupHits.reduce((sum, h) => sum + h.count, 0)
          const found = isItem ? (foundPerItem[key] ?? 0) : 0
          const done = isItem && found >= quota
          const duration = isItem
            ? itemDurationNanos[key]
            : structureGroupNanos(key, elapsedNanosPerStructure)
          const searching = isItem && !done && running
          const incomplete = isItem && !done && !running
          const titleTone = searching ? 'text-accent' : incomplete ? 'text-warn' : ''
          const countTone = done
            ? 'text-good'
            : searching
              ? 'text-accent'
              : incomplete
                ? 'text-warn'
                : 'text-good'
          return (
            <div key={key} className="border-b border-ink-700">
              <button
                type="button"
                onClick={() => toggle(key)}
                className={`${HEADER_BTN} sticky top-0 z-10 bg-ink-800 border-b border-ink-600`}
              >
                <div className={`${ITEM_GRID} py-1 px-1`}>
                  <span className="text-accent text-base leading-none select-none">
                    {open ? '▾' : '▸'}
                  </span>
                  <span className="flex items-center gap-1 min-w-0">
                    {isItem && (
                      <img
                        src={itemIconPath(key)}
                        alt=""
                        className="w-4 h-4 pixelated shrink-0"
                        onError={(e) => {
                          ;(e.target as HTMLImageElement).style.display = 'none'
                        }}
                      />
                    )}
                    <span
                      className={`font-semibold truncate ${titleTone}`}
                      title={title}
                    >
                      {title}
                    </span>
                    {first && <MethodBadges methods={groupHits.map((h) => h.method)} />}
                  </span>
                  {isItem ? (
                    <span className={`text-right ${countTone}`}>
                      {found}/{quota}
                    </span>
                  ) : (
                    <span className="text-good text-left">×{total}</span>
                  )}
                  <span className="text-[#94A3B8] text-right">
                    {formatSeconds(duration)}
                  </span>
                  <span
                    className="col-span-3 text-[#94A3B8] text-xs whitespace-nowrap"
                    title={subtitle}
                  >
                    {subtitle}
                  </span>
                </div>
              </button>
              {open &&
                groupHits.map((hit, index) => (
                  <HitRow
                    key={`${key}-${index}`}
                    hit={hit}
                    mode={mode}
                    marked={marked}
                    onToggleMark={onToggleMark}
                    centerX={centerX}
                    centerZ={centerZ}
                    rank={
                      mode === 'structure'
                        ? ranks.get(`${hit.structureId}#${hit.instanceIndex}`)
                        : undefined
                    }
                  />
                ))}
            </div>
          )
        })}
      </div>
    </div>
  )
}
