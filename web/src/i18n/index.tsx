import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import namesEnJson from './names_en.json'
import {
  dimensionLabel as pickDimensionLabel,
  mergeLabel as pickMergeLabel,
  messages,
  methodLabel as pickMethodLabel,
  sequenceLabel as pickSequenceLabel,
  type Locale,
  type MessageKey,
} from './messages'
import { STRUCTURE_MERGES } from '../types'

export type { Locale }

const namesEn = namesEnJson as {
  items: Record<string, string>
  structures: Record<string, string>
  groups: Record<string, string>
}

const STORAGE_KEY = 'lootradar.locale'

export function detectLocale(): Locale {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'zh' || saved === 'en') return saved
  } catch {
    // localStorage 不可用时退回浏览器语言。
  }
  return navigator.language.toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

export interface I18n {
  locale: Locale
  setLocale: (locale: Locale) => void
  t: (key: MessageKey, vars?: Record<string, string | number>) => string
  itemName: (id: string, zhFallback?: string) => string
  structureName: (id: string, zhFallback?: string) => string
  groupName: (id: string, zhFallback?: string) => string
  methodLabel: (method: string) => string
  dimensionLabel: (dimension: string) => string
  sourceLabel: (hit: { method: string; structureZhName: string; structureId?: string }) => string
  structureFilterLabel: (name: string) => string
}

const I18nContext = createContext<I18n | null>(null)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(detectLocale)

  useEffect(() => {
    document.documentElement.lang = locale === 'zh' ? 'zh-CN' : 'en'
    document.title = `Loot Radar · ${messages[locale]['title.suffix']}`
  }, [locale])

  const setLocale = (next: Locale) => {
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 忽略写入失败，仅本次会话生效。
    }
    setLocaleState(next)
  }

  const value = useMemo<I18n>(() => {
    const enNames = locale === 'en'
    const pick = (
      map: Record<string, string>,
      id: string,
      zhFallback?: string,
    ): string => (enNames ? (map[id] ?? zhFallback ?? id) : (zhFallback ?? id))
    const t = (key: MessageKey, vars?: Record<string, string | number>): string => {
      let text: string = messages[locale][key] ?? messages.en[key] ?? key
      if (vars) {
        for (const [name, v] of Object.entries(vars)) {
          text = text.split(`{${name}}`).join(String(v))
        }
      }
      return text
    }
    const itemName = (id: string, zhFallback?: string) => pick(namesEn.items, id, zhFallback)
    const structureName = (id: string, zhFallback?: string) =>
      pick(namesEn.structures, id, zhFallback)
    const groupName = (id: string, zhFallback?: string) => pick(namesEn.groups, id, zhFallback)
    return {
      locale,
      setLocale,
      t,
      itemName,
      structureName,
      groupName,
      methodLabel: (method) => pickMethodLabel(locale, method),
      dimensionLabel: (dimension) => pickDimensionLabel(locale, dimension),
      sourceLabel: (hit) => {
        const merged = hit.structureId
          ? pickMergeLabel(locale, STRUCTURE_MERGES[hit.structureId] ?? hit.structureId)
          : undefined
        return (
          pickSequenceLabel(locale, hit.method) ??
          merged ??
          structureName(hit.structureId ?? '', hit.structureZhName)
        )
      },
      structureFilterLabel: (name) =>
        locale === 'zh'
          ? name.replace(/（[^（）]*）$/, '').replace(/^(暖水|冷水)/, '')
          : name.replace(/\s*\([^()]*\)$/, '').replace(/^(Warm|Cold) /, ''),
    }
  }, [locale])

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18n {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used within I18nProvider')
  return ctx
}
