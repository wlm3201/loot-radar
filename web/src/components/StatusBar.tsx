import type { SearchReport } from '../types'

import { shutdownApp } from '../api'

// 底部状态条：运行中显示本地估算耗时，结束后改用后端 report.totalElapsedNanos。
interface Props {
  running: boolean
  report: SearchReport | null
  error: string | null
  totalElapsedNanos: number
  cancelled?: boolean
}

function formatNanos(nanos: number): string {
  if (nanos <= 0) return '—'
  return `${(nanos / 1_000_000_000).toFixed(2)}s`
}

export function StatusBar({ running, report, error, totalElapsedNanos, cancelled }: Props) {
  return (
    <div className="panel px-3 py-2 flex items-center gap-4 text-xs">
      <span className={running ? 'text-accent' : 'text-[#94A3B8]'}>
        {running
          ? '● 搜索中'
          : cancelled || report?.cancelled
            ? '■ 已停止'
            : report
              ? '✓ 完成'
              : '○ 待命'}
      </span>
      {totalElapsedNanos > 0 && (
        <span className="text-accent">总耗时 {formatNanos(totalElapsedNanos)}</span>
      )}
      {error && <span className="text-bad">错误：{error}</span>}
      <button
        type="button"
        className="ml-auto text-[#94A3B8] hover:text-bad"
        title="停止后端进程并关闭本工具"
        onClick={() => {
          if (window.confirm('确定退出 loot-radar？后端进程将停止。')) {
            void shutdownApp().then(() => window.close())
          }
        }}
      >
        退出应用
      </button>
    </div>
  )
}
