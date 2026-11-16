import type { Catalog, SearchHit, SearchProgress, SearchReport, SearchRequest } from './types'

async function json<T>(input: string, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init)
  if (!response.ok) {
    throw new Error(`${input} -> HTTP ${response.status}`)
  }
  return (await response.json()) as T
}

export function fetchCatalog(version?: string): Promise<Catalog> {
  return json<Catalog>(
    version ? `/api/catalog?version=${encodeURIComponent(version)}` : '/api/catalog',
  )
}

export interface ActiveSearch {
  searchId: number
  finished: boolean
}

export function fetchActive(): Promise<ActiveSearch> {
  return json<ActiveSearch>('/api/search/active')
}

export function fetchVersions(): Promise<{ versions: string[]; active: string }> {
  return json('/api/versions')
}

export function startSearch(request: SearchRequest): Promise<{ searchId: number }> {
  return json('/api/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
}

export interface SearchCallbacks {
  onHit: (hit: SearchHit) => void
  onProgress: (progress: SearchProgress) => void
  onDone: (report: SearchReport) => void
  onError: (message: string) => void
  // 无 data 的 error 是网络级断连；EventSource 仍会自动重连，这里只提示不关闭。
  // 提示文案由调用方按当前语言给出。
  onDisconnect?: () => void
}

// 服务端缓存全部事件并支持 Last-Event-ID 续传；这里只处理带 data 的业务错误，
// 网络级错误交给 EventSource 自动重连。
export function streamSearch(searchId: number, callbacks: SearchCallbacks): () => void {
  const source = new EventSource(`/api/search/${searchId}/events`)
  source.addEventListener('hit', (event) => {
    callbacks.onHit(JSON.parse((event as MessageEvent).data) as SearchHit)
  })
  source.addEventListener('progress', (event) => {
    callbacks.onProgress(JSON.parse((event as MessageEvent).data) as SearchProgress)
  })
  source.addEventListener('done', (event) => {
    callbacks.onDone(JSON.parse((event as MessageEvent).data) as SearchReport)
    source.close()
  })
  source.addEventListener('error', (event) => {
    const data = (event as MessageEvent).data
    if (data) {
      const payload = JSON.parse(data) as { message: string }
      callbacks.onError(payload.message)
      source.close()
    } else {
      callbacks.onDisconnect?.()
    }
  })
  return () => source.close()
}

export function cancelSearch(searchId: number): void {
  void fetch(`/api/search/${searchId}/cancel`, { method: 'POST' })
}

export async function shutdownApp(): Promise<void> {
  await fetch('/api/shutdown', { method: 'POST' })
}
