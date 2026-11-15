import { useEffect, useMemo, useRef, useState } from 'react'
import {
  cancelSearch,
  fetchActive,
  fetchCatalog,
  fetchVersions,
  startSearch,
  streamSearch,
} from './api'
import type { Catalog, SearchHit, SearchProgress, SearchReport, SearchRequest } from './types'
import { ItemPicker } from './components/ItemPicker'
import { MapView } from './components/MapView'
import { ResultTable } from './components/ResultTable'
import { SearchForm } from './components/SearchForm'
import { StatusBar } from './components/StatusBar'

type ViewMode = 'item' | 'structure' | 'map'

// 状态中枢：目录/版本/物品选择、搜索会话与 SSE 事件流都在这里汇聚后分发给各视图。
export default function App() {
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [versions, setVersions] = useState<string[]>(['26.2'])
  const [version, setVersion] = useState<string>('26.2')
  const [selected, setSelected] = useState<string[]>([])
  const [view, setView] = useState<ViewMode>('item')
  const [hits, setHits] = useState<SearchHit[]>([])
  const [progress, setProgress] = useState<SearchProgress | null>(null)
  const [report, setReport] = useState<SearchReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState(false)
  // 取消会主动关闭 SSE，收不到后端的 done(report.cancelled)，这里单独记录停止状态。
  const [cancelled, setCancelled] = useState(false)
  const [request, setRequest] = useState<SearchRequest | null>(null)
  const [startedAt, setStartedAt] = useState(0)
  const [now, setNow] = useState(0)
  const closeStream = useRef<(() => void) | null>(null)
  const searchId = useRef<number | null>(null)

  const [marked, setMarked] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem('lootradar.marked') ?? '[]') as string[])
    } catch {
      return new Set()
    }
  })
  useEffect(() => {
    localStorage.setItem('lootradar.marked', JSON.stringify([...marked]))
  }, [marked])
  const toggleMark = (key: string) => {
    setMarked((current) => {
      const next = new Set(current)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  // 挂载、新搜索、刷新恢复都走这里；服务端会缓存全部事件，所以可以全量重放。
  const attach = (id: number, initialFinished: boolean) => {
    closeStream.current?.()
    searchId.current = id
    setStartedAt(Date.now())
    setRunning(!initialFinished)
    setCancelled(false)
    closeStream.current = streamSearch(id, {
      onHit: (hit) => {
        setError(null)
        setHits((current) => [...current, hit])
      },
      onProgress: (value) => {
        setError(null)
        setProgress(value)
      },
      onDone: (value) => {
        setReport(value)
        setRunning(false)
      },
      onError: (message) => {
        setError(message)
        setRunning(false)
      },
      onDisconnect: (message) => setError(message),
    })
  }

  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    void fetchActive()
      .then((active) => {
        if (searchId.current !== null) return
        if (active && active.searchId > 0) {
          setHits([])
          setProgress(null)
          setReport(null)
          attach(active.searchId, active.finished)
        }
      })
      .catch((error: Error) => setLoadError(error.message))
    void fetchVersions()
      .then((data) => {
        setVersions(data.versions)
        setVersion(data.active)
      })
      .catch(() => undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    void fetchCatalog(version)
      .then((data) => {
        setCatalog(data)
        setSelected((previous) => {
          if (previous.length === 0) {
            return data.items
              .filter((item) => data.groups.find((g) => g.id === item.group)?.defaultSelected)
              .map((item) => item.id)
          }
          const have = new Set(data.items.map((item) => item.id))
          return previous.filter((id) => have.has(id))
        })
      })
      .catch((error: Error) => {
        // 版本切换失败时不能继续展示旧目录，否则用户看不到报错。
        setLoadError(error.message)
        setCatalog(null)
      })
  }, [version])

  useEffect(() => {
    if (!running) return
    const timer = window.setInterval(() => setNow(Date.now()), 200)
    return () => window.clearInterval(timer)
  }, [running])
  useEffect(() => {
    if (!running && startedAt > 0) setNow(Date.now())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running])

  const elapsedNanos = startedAt > 0 ? (now - startedAt) * 1_000_000 : 0

  const runSearch = (next: SearchRequest) => {
    setHits([])
    setProgress(null)
    setReport(null)
    setError(null)
    setRunning(true)
    setCancelled(false)
    setRequest(next)
    setStartedAt(Date.now())
    void startSearch(next)
      .then(({ searchId: id }) => attach(id, false))
      .catch((error: Error) => {
        setError(error.message)
        setRunning(false)
      })
  }

  const cancel = () => {
    if (searchId.current !== null) {
      cancelSearch(searchId.current)
    }
    closeStream.current?.()
    setRunning(false)
    setCancelled(true)
  }

  const itemZhNames = useMemo(() => {
    const map = new Map<string, string>()
    catalog?.items.forEach((item) => map.set(item.id, item.zhName))
    return map
  }, [catalog])

  if (!catalog) {
    return (
      <div className="p-6 flex flex-col gap-3 items-start">
        {loadError ? (
          <>
            <span className="text-bad">无法连接后端：{loadError}</span>
            <button type="button" onClick={() => window.location.reload()}>
              重试连接
            </button>
          </>
        ) : (
          <span className="text-[#94A3B8]">加载目录中…</span>
        )}
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col gap-2 p-2">
      <header className="panel px-4 py-2 flex items-center gap-3">
        <span className="text-accent font-bold text-lg">Loot Radar</span>
        <span className="text-[#94A3B8] text-xs">Minecraft 26.x 战利品查找器</span>
        {request && (
          <span className="ml-auto text-xs text-[#94A3B8]">
            种子 {request.seed} · 中心 {request.centerX},{request.centerZ} · 范围 {request.radius}
          </span>
        )}
      </header>

      <div className="flex-1 grid grid-cols-[300px_minmax(260px,370px)_1fr] gap-2 min-h-0">
        <div className="flex flex-col gap-2 min-h-0 overflow-y-auto">
          <SearchForm
            versions={versions}
            version={version}
            onVersionChange={setVersion}
            running={running}
            onSearch={runSearch}
            onCancel={cancel}
            itemIds={selected}
            items={catalog?.items ?? []}
            structureNames={catalog?.structures ?? {}}
          />
        </div>

        <div className="flex flex-col gap-2 min-h-0 overflow-y-auto">
          <ItemPicker catalog={catalog} selected={selected} onChange={setSelected} />
        </div>

        <div className="flex flex-col gap-2 min-h-0">
          <div className="flex gap-2">
            {(
              [
                ['item', '物品'],
                ['structure', '结构'],
                ['map', '雷达'],
              ] as const
            ).map(([mode, label]) => (
              <button
                key={mode}
                type="button"
                onClick={() => setView(mode)}
                className={view === mode ? 'border-accent text-accent' : ''}
              >
                {label}
              </button>
            ))}
            <span className="ml-auto text-xs text-[#94A3B8] self-center">
              命中 {hits.length} 条
            </span>
          </div>

          <div className="flex-1 min-h-0">
            {view === 'map' ? (
              <MapView
                hits={hits}
                centerX={request?.centerX ?? 0}
                centerZ={request?.centerZ ?? 0}
                radius={request?.radius ?? 5000}
                marked={marked}
                onToggleMark={toggleMark}
              />
            ) : (
              <ResultTable
                hits={hits}
                mode={view === 'item' ? 'item' : 'structure'}
                itemIds={request?.itemIds ?? []}
                itemZhNames={itemZhNames}
                itemDurationNanos={
                  report?.itemDurationNanos ?? progress?.itemDurationNanos ?? {}
                }
                costNanosPerItem={progress?.costNanosPerItem ?? report?.costNanosPerItem ?? {}}
                elapsedNanosPerStructure={
                  report?.elapsedNanosPerStructure ?? progress?.elapsedNanosPerStructure ?? {}
                }
                foundPerItem={progress?.foundPerItem ?? report?.foundPerItem ?? {}}
                quota={request?.minCountPerItem ?? 3}
                marked={marked}
                onToggleMark={toggleMark}
                running={running}
                centerX={request?.centerX ?? 0}
                centerZ={request?.centerZ ?? 0}
              />
            )}
          </div>

          <StatusBar
            running={running}
            report={report}
            error={error}
            // 结束后以服务端报告为准；搜索中才用本地计时，避免刷新恢复后显示错误耗时。
            totalElapsedNanos={report?.totalElapsedNanos ?? elapsedNanos}
            cancelled={cancelled}
          />
        </div>
      </div>
    </div>
  )
}
