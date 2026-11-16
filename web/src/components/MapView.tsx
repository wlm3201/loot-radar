import { useEffect, useMemo, useRef, useState } from 'react'
import type { SearchHit } from '../types'
import { denseInstanceRanks, hitKey, mergeSameLocation } from '../types'
import { useI18n } from '../i18n'
import { itemIconPath } from './ResultTable'

// Canvas 雷达图：把同一结构实例的命中聚成一个点，支持缩放/平移与「已获取」标记。
interface Props {
  hits: SearchHit[]
  centerX: number
  centerZ: number
  radius: number
  marked: Set<string>
  onToggleMark: (key: string) => void
}

interface Cluster {
  structureZhName: string
  x: number
  z: number
  instanceIndex: number
  hits: SearchHit[]
}

const DIMENSIONS = ['overworld', 'the_nether', 'the_end'] as const

// 雷达详情的坐标与物品/结构视图保持一致：复制后短暂显示「已复制」反馈。
function RadarCoords({ hit, distance }: { hit: SearchHit; distance: number }) {
  const { t } = useI18n()
  const [flash, setFlash] = useState<string | null>(null)
  const coords = `${hit.x} ${hit.y} ${hit.z}`
  const copy = (text: string, label: string) => {
    void navigator.clipboard.writeText(text)
    setFlash(label)
    window.setTimeout(() => setFlash(null), 900)
  }
  return (
    <span className="whitespace-nowrap">
      <span
        className="font-mono text-[#94A3B8] cursor-pointer hover:text-white"
        title={t('results.copyTip')}
        onClick={() => copy(coords, t('results.copied'))}
        onContextMenu={(e) => {
          e.preventDefault()
          copy(`/tp ${coords}`, t('results.copiedTp'))
        }}
      >
        {flash ?? coords}
      </span>
      <span className="text-[#64748B]"> {t('map.distance', { count: distance })}</span>
    </span>
  )
}

export function MapView({ hits, centerX, centerZ, radius, marked, onToggleMark }: Props) {
  const { t, itemName, dimensionLabel, sourceLabel } = useI18n()
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const wrapRef = useRef<HTMLDivElement>(null)
  const [dimension, setDimension] = useState<string>('overworld')
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const [selected, setSelected] = useState<Cluster | null>(null)
  const [hovered, setHovered] = useState<Cluster | null>(null)
  const [iconsTick, setIconsTick] = useState(0)
  const dragState = useRef<{ x: number; y: number; moved: boolean } | null>(null)
  const iconCache = useRef(new Map<string, HTMLImageElement>())

  const getIcon = (itemId: string): HTMLImageElement | null => {
    const cached = iconCache.current.get(itemId)
    if (cached) return cached.complete && cached.naturalWidth > 0 ? cached : null
    const img = new Image()
    img.src = itemIconPath(itemId)
    img.onload = () => setIconsTick((t) => t + 1)
    iconCache.current.set(itemId, img)
    return null
  }

  const dimensionHits = useMemo(
    () => hits.filter((h) => h.dimension === dimension && h.nth < 1),
    [hits, dimension],
  )
  const ranks = useMemo(() => denseInstanceRanks(hits), [hits])

  // 同一结构实例可能有多件目标物品；按 structureId#instanceIndex 聚成一个点，
  // 坐标取所有命中平均值，避免一个箱子上叠多个图标。
  const clusters = useMemo(() => {
    const map = new Map<string, Cluster & { sumX: number; sumZ: number; count: number }>()
    for (const hit of dimensionHits) {
      const key = `${hit.structureId}#${hit.instanceIndex}`
      const existing = map.get(key)
      if (existing) {
        existing.hits.push(hit)
        existing.sumX += hit.x
        existing.sumZ += hit.z
        existing.count++
      } else {
        map.set(key, {
          structureZhName: hit.structureZhName,
          x: hit.x,
          z: hit.z,
          instanceIndex: hit.instanceIndex,
          hits: [hit],
          sumX: hit.x,
          sumZ: hit.z,
          count: 1,
        })
      }
    }
    return [...map.values()].map((entry) => ({
      structureZhName: entry.structureZhName,
      instanceIndex: entry.instanceIndex,
      hits: entry.hits,
      x: Math.round(entry.sumX / entry.count),
      z: Math.round(entry.sumZ / entry.count),
    }))
  }, [dimensionHits])

  useEffect(() => {
    const canvas = canvasRef.current
    const wrap = wrapRef.current
    if (!canvas || !wrap) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const draw = () => {
      const dpr = window.devicePixelRatio || 1
      const width = wrap.clientWidth
      const height = wrap.clientHeight
      if (width === 0 || height === 0) return
      canvas.width = Math.round(width * dpr)
      canvas.height = Math.round(height * dpr)
      canvas.style.width = `${width}px`
      canvas.style.height = `${height}px`
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

      ctx.fillStyle = '#0B1220'
      ctx.fillRect(0, 0, width, height)

      const scale = (Math.min(width, height) / (radius * 2.2)) * zoom
      const toScreen = (x: number, z: number): [number, number] => [
        width / 2 + (x - centerX) * scale + pan.x,
        height / 2 + (z - centerZ) * scale + pan.y,
      ]

      const [cx, cy] = toScreen(centerX, centerZ)
      ctx.strokeStyle = '#263449'
      ctx.beginPath()
      ctx.arc(cx, cy, radius * scale, 0, Math.PI * 2)
      ctx.stroke()

      const steps = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000]
      const step = steps.find((s) => s * scale >= 72) ?? 10000
      const leftWorld = centerX - (width / 2 + pan.x) / scale
      const rightWorld = centerX + (width / 2 - pan.x) / scale
      const topWorld = centerZ - (height / 2 + pan.y) / scale
      const bottomWorld = centerZ + (height / 2 - pan.y) / scale
      ctx.strokeStyle = 'rgba(38,52,73,0.45)'
      for (let x = Math.ceil(leftWorld / step) * step; x <= rightWorld; x += step) {
        const [gx] = toScreen(x, centerZ)
        if (gx < 0 || gx > width) continue
        ctx.beginPath()
        ctx.moveTo(gx, 0)
        ctx.lineTo(gx, height)
        ctx.stroke()
      }
      for (let z = Math.ceil(topWorld / step) * step; z <= bottomWorld; z += step) {
        const [, gy] = toScreen(centerX, z)
        if (gy < 0 || gy > height) continue
        ctx.beginPath()
        ctx.moveTo(0, gy)
        ctx.lineTo(width, gy)
        ctx.stroke()
      }

      ctx.font = '10px sans-serif'
      ctx.fillStyle = '#FFFFFF'
      ctx.strokeStyle = '#263449'
      for (let x = Math.ceil(leftWorld / step) * step; x <= rightWorld; x += step) {
        const [sx] = toScreen(x, centerZ)
        if (sx < 0 || sx > width) continue
        ctx.beginPath()
        ctx.moveTo(sx, height - 8)
        ctx.lineTo(sx, height)
        ctx.stroke()
        ctx.fillText(String(x), sx + 2, height - 10)
      }
      for (let z = Math.ceil(topWorld / step) * step; z <= bottomWorld; z += step) {
        const [, sy] = toScreen(centerX, z)
        if (sy < 0 || sy > height) continue
        ctx.beginPath()
        ctx.moveTo(0, sy)
        ctx.lineTo(8, sy)
        ctx.stroke()
        ctx.fillText(String(z), 10, sy - 2)
      }

      const margin = 24
      const inside = cx >= margin && cx <= width - margin && cy >= margin && cy <= height - margin
      const ex = Math.min(width - margin, Math.max(margin, cx))
      const ey = Math.min(height - margin, Math.max(margin, cy))
      ctx.strokeStyle = '#22D3EE'
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.moveTo(ex - 8, ey)
      ctx.lineTo(ex + 8, ey)
      ctx.moveTo(ex, ey - 8)
      ctx.lineTo(ex, ey + 8)
      ctx.stroke()
      ctx.lineWidth = 1
      ctx.fillStyle = '#22D3EE'
      ctx.fillText(t('map.center'), ex + 10, ey - 6)
      if (!inside) {
        const angle = Math.atan2(cy - ey, cx - ex)
        ctx.beginPath()
        ctx.moveTo(ex + Math.cos(angle) * 14, ey + Math.sin(angle) * 14)
        ctx.lineTo(ex + Math.cos(angle + 2.5) * 8, ey + Math.sin(angle + 2.5) * 8)
        ctx.lineTo(ex + Math.cos(angle - 2.5) * 8, ey + Math.sin(angle - 2.5) * 8)
        ctx.closePath()
        ctx.fill()
      }

      if (selected) {
        const [sx, sy] = toScreen(selected.x, selected.z)
        ctx.save()
        ctx.setLineDash([6, 5])
        ctx.strokeStyle = '#22D3EE'
        ctx.lineWidth = 1
        ctx.beginPath()
        ctx.moveTo(sx, sy)
        ctx.lineTo(ex, ey)
        ctx.stroke()
        ctx.restore()
      }

      for (const cluster of clusters) {
        const [sx, sy] = toScreen(cluster.x, cluster.z)
        if (sx < -40 || sy < -40 || sx > width + 40 || sy > height + 40) continue
        const active = selected === cluster || hovered === cluster
        const allMarked = cluster.hits.every((h) => marked.has(hitKey(h)))
        const icon = getIcon(cluster.hits[0].itemId)

        if (active) {
          ctx.fillStyle = 'rgba(34,211,238,0.15)'
          ctx.beginPath()
          ctx.arc(sx, sy, 15, 0, Math.PI * 2)
          ctx.fill()
          ctx.strokeStyle = '#22D3EE'
          ctx.lineWidth = 2
          ctx.beginPath()
          ctx.arc(sx, sy, 14, 0, Math.PI * 2)
          ctx.stroke()
          ctx.lineWidth = 1
        }
        if (icon) {
          ctx.imageSmoothingEnabled = false
          ctx.globalAlpha = allMarked ? 0.4 : 1
          ctx.drawImage(icon, sx - 10, sy - 10, 20, 20)
          ctx.globalAlpha = 1
        } else {
          ctx.fillStyle = allMarked ? '#64748B' : '#22D3EE'
          ctx.beginPath()
          ctx.arc(sx, sy, active ? 8 : 5, 0, Math.PI * 2)
          ctx.fill()
        }
        ctx.fillStyle = allMarked ? '#64748B' : active ? '#22D3EE' : '#FFFFFF'
        ctx.font = '11px sans-serif'
        const rank = ranks.get(`${cluster.hits[0].structureId}#${cluster.instanceIndex}`)
        const label = sourceLabel(cluster.hits[0])
        ctx.fillText(`${label} #${rank ?? cluster.instanceIndex}`, sx + 12, sy + 4)
      }
    }

    draw()
    const observer = new ResizeObserver(draw)
    observer.observe(wrap)
    return () => observer.disconnect()
  }, [
    clusters,
    ranks,
    centerX,
    centerZ,
    radius,
    zoom,
    pan,
    selected,
    hovered,
    marked,
    iconsTick,
    t,
    sourceLabel,
  ])

  const hitTest = (event: React.MouseEvent<HTMLCanvasElement>): Cluster | null => {
    const canvas = canvasRef.current
    if (!canvas) return null
    const rect = canvas.getBoundingClientRect()
    const width = rect.width
    const height = rect.height
    const mx = event.clientX - rect.left
    const my = event.clientY - rect.top
    const scale = (Math.min(width, height) / (radius * 2.2)) * zoom
    let best: Cluster | null = null
    let bestDistance = 18
    for (const cluster of clusters) {
      const sx = width / 2 + (cluster.x - centerX) * scale + pan.x
      const sy = height / 2 + (cluster.z - centerZ) * scale + pan.y
      const distance = Math.hypot(mx - sx, my - sy)
      if (distance < bestDistance) {
        best = cluster
        bestDistance = distance
      }
    }
    return best
  }

  return (
    <div className="panel relative h-full overflow-hidden">
      <div className="absolute top-2 left-2 z-10 flex gap-1">
        {DIMENSIONS.map((dim) => (
          <button
            key={dim}
            type="button"
            onClick={() => {
              setDimension(dim)
              setSelected(null)
              setPan({ x: 0, y: 0 })
              setZoom(1)
            }}
            className={
              dimension === dim
                ? 'border-accent text-accent text-xs px-2 py-0.5'
                : 'text-xs px-2 py-0.5'
            }
          >
            {dimensionLabel(dim)}
          </button>
        ))}
      </div>
      <div ref={wrapRef} className="w-full h-full">
        <canvas
          ref={canvasRef}
          className="w-full h-full cursor-grab active:cursor-grabbing"
          onWheel={(e) => {
            const wrap = wrapRef.current
            if (!wrap) return
            const width = wrap.clientWidth
            const height = wrap.clientHeight
            const rect = wrap.getBoundingClientRect()
            const mx = e.clientX - rect.left
            const my = e.clientY - rect.top
            const oldScale = (Math.min(width, height) / (radius * 2.2)) * zoom
            const worldX = (mx - width / 2 - pan.x) / oldScale + centerX
            const worldZ = (my - height / 2 - pan.y) / oldScale + centerZ
            const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15
            const nextZoom = Math.min(12, Math.max(0.5, zoom * factor))
            const nextScale = (Math.min(width, height) / (radius * 2.2)) * nextZoom
            setZoom(nextZoom)
            setPan({
              x: mx - width / 2 - (worldX - centerX) * nextScale,
              y: my - height / 2 - (worldZ - centerZ) * nextScale,
            })
          }}
          onMouseDown={(e) => {
            dragState.current = { x: e.clientX, y: e.clientY, moved: false }
          }}
          onMouseMove={(e) => {
            if (dragState.current) {
              const dx = e.clientX - dragState.current.x
              const dy = e.clientY - dragState.current.y
              if (Math.abs(dx) + Math.abs(dy) > 2) {
                dragState.current.moved = true
              }
              setPan((p) => ({ x: p.x + dx, y: p.y + dy }))
              dragState.current.x = e.clientX
              dragState.current.y = e.clientY
              return
            }
            setHovered(hitTest(e))
          }}
          onMouseUp={(e) => {
            const moved = dragState.current?.moved
            dragState.current = null
            if (!moved) {
              setSelected(hitTest(e))
            }
          }}
          onMouseLeave={() => {
            dragState.current = null
            setHovered(null)
          }}
        />
      </div>
      {selected && (
        <div className="absolute top-3 right-3 w-80 panel p-3 flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <span className="font-semibold">
              {sourceLabel(selected.hits[0])} #{ranks.get(`${selected.hits[0].structureId}#${selected.instanceIndex}`) ?? selected.instanceIndex}
            </span>
            <button type="button" className="text-xs px-2 py-0.5" onClick={() => setSelected(null)}>
              {t('map.close')}
            </button>
          </div>
          <div className="text-xs text-[#94A3B8]">
            {dimensionLabel(selected.hits[0].dimension)}
            {' · '}
            {t('map.refCoords', { x: selected.x, z: selected.z })}
          </div>
          <div className="flex flex-col gap-1 max-h-64 overflow-y-auto">
            {mergeSameLocation(selected.hits).map((hit, index) => {
              const key = hitKey(hit)
              const isMarked = marked.has(key)
              const distance = Math.round(Math.hypot(hit.x - centerX, hit.z - centerZ))
              return (
                <div
                  key={index}
                  className={
                    'flex items-center gap-2 text-xs border-t border-ink-700 pt-1 ' +
                    (isMarked ? 'opacity-45' : '')
                  }
                >
                  <img
                    src={itemIconPath(hit.itemId)}
                    alt=""
                    className="w-4 h-4 pixelated shrink-0"
                    onError={(e) => {
                      ;(e.target as HTMLImageElement).style.display = 'none'
                    }}
                  />
                  <span className="flex-1 truncate">
                    {itemName(hit.itemId, hit.itemZhName)} ×{hit.count}
                  </span>
                  <RadarCoords hit={hit} distance={distance} />
                  <button
                    type="button"
                    title={isMarked ? t('map.unmarkTip') : t('results.mark')}
                    onClick={() => onToggleMark(key)}
                    className={
                      'px-1.5 py-0.5 text-xs ' +
                      (isMarked
                        ? 'border-good text-good bg-good/10'
                        : 'border-ink-600 text-[#64748B] hover:text-white')
                    }
                  >
                    {isMarked ? '✔' : '□'}
                  </button>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
