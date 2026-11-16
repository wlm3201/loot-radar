import type { SearchReport } from '../types'

import { useI18n } from '../i18n'

// 搜索状态：直接铺在结果区 tab 栏的「命中 n 条」旁边，不再占用底部独立状态条。
interface Props {
  running: boolean
  report: SearchReport | null
  error: string | null
  totalElapsedNanos: number
  structuresScanned: number
  containersReplayed: number
  cancelled: boolean
}

function formatNanos(nanos: number): string {
  if (nanos <= 0) return '—'
  return `${(nanos / 1_000_000_000).toFixed(2)}s`
}

export function SearchStatus({
  running,
  report,
  error,
  totalElapsedNanos,
  structuresScanned,
  containersReplayed,
  cancelled,
}: Props) {
  const { t } = useI18n()
  return (
    <>
      <span
        className={`text-xs whitespace-nowrap ${running ? 'text-accent' : 'text-[#94A3B8]'}`}
      >
        {running
          ? t('status.searching')
          : cancelled || report?.cancelled
            ? t('status.stopped')
            : report
              ? t('status.done')
              : t('status.idle')}
      </span>
      {totalElapsedNanos > 0 && (
        <span className="text-xs text-accent whitespace-nowrap">
          {t('status.total', { time: formatNanos(totalElapsedNanos) })}
        </span>
      )}
      {structuresScanned > 0 && (
        <span className="text-xs text-[#94A3B8] whitespace-nowrap">
          {t('status.structures', { count: structuresScanned })}
        </span>
      )}
      {containersReplayed > 0 && (
        <span className="text-xs text-[#94A3B8] whitespace-nowrap">
          {t('status.containers', { count: containersReplayed })}
        </span>
      )}
      {error && (
        <span className="text-xs text-bad whitespace-nowrap">
          {t('status.error')}
          {error}
        </span>
      )}
    </>
  )
}
