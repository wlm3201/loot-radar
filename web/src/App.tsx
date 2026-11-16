import { useEffect, useMemo, useRef, useState } from 'react'
import {
  cancelSearch,
  fetchActive,
  fetchCatalog,
  fetchVersions,
  shutdownApp,
  startSearch,
  streamSearch,
} from './api'
import type { Catalog, SearchHit, SearchProgress, SearchReport, SearchRequest } from './types'
import { useI18n } from './i18n'
import { ItemPicker } from './components/ItemPicker'
import { MapView } from './components/MapView'
import { ResultTable } from './components/ResultTable'
import { SearchForm } from './components/SearchForm'
import { SearchStatus } from './components/SearchStatus'

type ViewMode = 'item' | 'structure' | 'map'

// 状态中枢：目录/版本/物品选择、搜索会话与 SSE 事件流都在这里汇聚后分发给各视图。
function AppInner() {
  const { t, locale, setLocale, itemName } = useI18n()
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [versions, setVersions] = useState<string[]>(['26.2'])
  const [version, setVersion] = useState<string>('26.2')
  const [selected, setSelected] = useState<string[]>([])
  const [view, setView] = useState<ViewMode>('item')
  const [hits, setHits] = useState<SearchHit[]>([])
  // hit 包自带累计扫描统计，收到即更新状态栏，无需等 progress/done。
  const [hitStats, setHitStats] = useState<{
    structuresScanned: number
    containersReplayed: number
  } | null>(null)
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
    setHitStats(null)
    closeStream.current = streamSearch(id, {
      onHit: (hit) => {
        setError(null)
        setHits((current) => [...current, hit])
        setHitStats({
          structuresScanned: hit.structuresScanned,
          containersReplayed: hit.containersReplayed,
        })
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
      onDisconnect: () => setError(t('api.disconnected')),
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

  // 全局禁用浏览器右键菜单；结果/雷达里的右键复制（/tp）在各自元素内处理，不受影响。
  useEffect(() => {
    const suppressMenu = (e: MouseEvent) => e.preventDefault()
    document.addEventListener('contextmenu', suppressMenu)
    return () => document.removeEventListener('contextmenu', suppressMenu)
  }, [])
  useEffect(() => {
    if (!running && startedAt > 0) setNow(Date.now())
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running])

  const elapsedNanos = startedAt > 0 ? (now - startedAt) * 1_000_000 : 0
  // 优先用最后一次命中携带的累计值；无命中时用 progress/report 兜底。
  const scanStats = report ?? hitStats ?? progress

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

  const itemNames = useMemo(() => {
    const map = new Map<string, string>()
    catalog?.items.forEach((item) => map.set(item.id, itemName(item.id, item.zhName)))
    return map
  }, [catalog, itemName])

  if (!catalog) {
    return (
      <div className="p-6 flex flex-col gap-3 items-start">
        {loadError ? (
          <>
            <span className="text-bad">
              {t('app.loadError')}
              {loadError}
            </span>
            <button type="button" onClick={() => window.location.reload()}>
              {t('app.retry')}
            </button>
          </>
        ) : (
          <span className="text-[#94A3B8]">{t('app.loading')}</span>
        )}
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col gap-2 p-2">
      <header className="panel px-4 py-2 flex items-center gap-3">
        <span className="text-accent font-bold text-lg">Loot Radar</span>
        <span className="text-[#94A3B8] text-xs">{t('app.tagline')}</span>
        <div className="ml-auto flex items-center gap-3">
          {request && (
            <span className="text-xs text-[#94A3B8]">
              {t('app.searchSummary', {
                seed: request.seed,
                x: request.centerX,
                z: request.centerZ,
                radius: request.radius,
              })}
            </span>
          )}
          <button
            type="button"
            className="text-xs px-2 py-0.5"
            title="Language / 语言"
            onClick={() => setLocale(locale === 'zh' ? 'en' : 'zh')}
          >
            {locale === 'zh' ? 'EN' : '中文'}
          </button>
          <button
            type="button"
            className="text-xs px-2 py-0.5 text-[#94A3B8] hover:text-bad"
            title={t('status.exitTip')}
            onClick={() => {
              if (window.confirm(t('status.exitConfirm'))) {
                void shutdownApp().then(() => window.close())
              }
            }}
          >
            {t('status.exit')}
          </button>
        </div>
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
          <div className="flex items-center gap-2">
            {(
              [
                ['item', t('app.viewItems')],
                ['structure', t('app.viewStructures')],
                ['map', t('app.viewMap')],
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
            <div className="ml-auto flex items-center gap-2">
              <SearchStatus
                running={running}
                report={report}
                error={error}
                // 结束后以服务端报告为准；搜索中才用本地计时，避免刷新恢复后显示错误耗时。
                totalElapsedNanos={report?.totalElapsedNanos ?? elapsedNanos}
                structuresScanned={scanStats?.structuresScanned ?? 0}
                containersReplayed={scanStats?.containersReplayed ?? 0}
                cancelled={cancelled}
              />
              <span className="text-xs text-[#94A3B8] whitespace-nowrap">
                {t('app.hits', { count: hits.length })}
              </span>
            </div>
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
                itemNames={itemNames}
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
        </div>
      </div>
    </div>
  )
}

export default AppInner
