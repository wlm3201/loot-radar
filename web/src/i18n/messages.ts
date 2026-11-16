// UI 文案目录。MC 专有名词（物品/结构名）不在这里，见 names_en.json（tools/extract-en-names.py 生成）。
export type Locale = 'zh' | 'en'

export const messages = {
  zh: {
    'app.tagline': 'Minecraft 26.x 战利品查找器',
    'app.searchSummary': '种子 {seed} · 中心 {x},{z} · 范围 {radius}',
    'app.viewItems': '物品',
    'app.viewStructures': '结构',
    'app.viewMap': '雷达',
    'app.hits': '命中 {count}',
    'app.loadError': '无法连接后端：',
    'app.retry': '重试连接',
    'app.loading': '加载目录中…',

    'form.seed': '世界种子',
    'form.centerX': '中心 X',
    'form.centerZ': '中心 Z',
    'form.version': '游戏版本',
    'form.minCount': '每物品数量',
    'form.startOffset': '初始宝库/击杀次数',
    'form.looting': '抢夺等级',
    'form.radius': '最大范围',
    'form.itemBudget': '每物品超时',
    'form.itemBudgetTip': '单个物品的累计搜索时间上限',
    'form.seedInvalid': '请输入 64 位有符号整数种子',
    'form.fieldsInvalid':
      '参数需为整数：范围 1~500000、数量 1~9999、中心 ±30000000、起始 1~100000、超时 0~86400',
    'form.searching': '搜索中…',
    'form.start': '开始搜索（{count} 项）',
    'form.stop': '停止',

    'picker.searchPlaceholder': '搜索物品（中文名或 ID）',
    'picker.clear': '清空选择',
    'picker.groupToggleTip': '点击全选，再次点击全取消',
    'picker.groupCount': '{name}（{selected}/{total}）',

    'filter.title': '候选结构',
    'filter.lockedTip': '{label}：所选物品中有仅剩此来源的，不可取消',
    'filter.excludeTip': '点击取消搜索{label}',
    'filter.includeTip': '点击恢复搜索{label}',

    'results.copyTip': '左键复制 x y z，右键复制 /tp',
    'results.copied': '已复制',
    'results.copiedTp': '已复制 /tp',
    'results.instanceRankTip': '第几处该结构',
    'results.nthTip': '第几次开启/击杀',
    'results.nth': '· 第{count}次',
    'results.distance': '{count}格',
    'results.unmark': '取消标记',
    'results.mark': '标记为已获取',
    'results.itemsCount': '物品 {count}',
    'results.structuresCount': '结构 {count}',
    'results.expandAll': '全部展开',
    'results.collapseAll': '全部折叠',
    'results.empty': '暂无结果，点击左侧「开始搜索」。',
    'results.subtitleJoin': '、',

    'status.searching': '● 搜索中',
    'status.stopped': '■ 已停止',
    'status.done': '✓ 完成',
    'status.idle': '○ 待命',
    'status.total': '总耗时 {time}',
    'status.structures': '结构 {count}',
    'status.containers': '容器 {count}',
    'status.error': '错误：',
    'status.exitTip': '停止后端进程并关闭本工具',
    'status.exitConfirm': '确定退出 loot-radar？后端进程将停止。',
    'status.exit': '退出应用',

    'map.center': '中心',
    'map.close': '关闭 ✕',
    'map.refCoords': '参考坐标 {x} {z}',
    'map.unmarkTip': '取消已获取标记',
    'map.distance': '{count}格',

    'api.disconnected': '与后端连接中断，正在自动重连…',

    'title.suffix': 'Minecraft 战利品查找器',
  },
  en: {
    'app.tagline': 'Minecraft 26.x loot finder',
    'app.searchSummary': 'Seed {seed} · Center {x},{z} · Radius {radius}',
    'app.viewItems': 'Items',
    'app.viewStructures': 'Structures',
    'app.viewMap': 'Radar',
    'app.hits': '{count} hits',
    'app.loadError': 'Cannot connect to backend: ',
    'app.retry': 'Retry',
    'app.loading': 'Loading catalog…',

    'form.seed': 'World Seed',
    'form.centerX': 'Center X',
    'form.centerZ': 'Center Z',
    'form.version': 'Game Version',
    'form.minCount': 'Count per Item',
    'form.startOffset': 'Initial Vault/Kills',
    'form.looting': 'Looting Level',
    'form.radius': 'Max Radius',
    'form.itemBudget': 'Timeout per Item',
    'form.itemBudgetTip': 'Max cumulative search time per item',
    'form.seedInvalid': 'Seed must be a 64-bit signed integer',
    'form.fieldsInvalid':
      'Integer params required: radius 1–500000, count 1–9999, center ±30,000,000, offset 1–100,000, timeout 0–86,400',
    'form.searching': 'Searching…',
    'form.start': 'Start Search ({count})',
    'form.stop': 'Stop',

    'picker.searchPlaceholder': 'Search items (name or ID)',
    'picker.clear': 'Clear',
    'picker.groupToggleTip': 'Select all, click again to clear',
    'picker.groupCount': '{name} ({selected}/{total})',

    'filter.title': 'Candidate Structures',
    'filter.lockedTip': '{label}: required by selected items, cannot disable',
    'filter.excludeTip': 'Click to exclude {label}',
    'filter.includeTip': 'Click to include {label}',

    'results.copyTip': 'Left-click to copy x y z, right-click to copy /tp',
    'results.copied': 'Copied',
    'results.copiedTp': 'Copied /tp',
    'results.instanceRankTip': 'Which instance of this structure',
    'results.nthTip': 'Open/kill sequence number',
    'results.nth': '· #{count}',
    'results.distance': '{count}b',
    'results.unmark': 'Unmark',
    'results.mark': 'Mark as obtained',
    'results.itemsCount': 'Items {count}',
    'results.structuresCount': 'Structures {count}',
    'results.expandAll': 'Expand all',
    'results.collapseAll': 'Collapse all',
    'results.empty': 'No results yet. Click "Start Search" on the left.',
    'results.subtitleJoin': ', ',

    'status.searching': '● Searching',
    'status.stopped': '■ Stopped',
    'status.done': '✓ Done',
    'status.idle': '○ Idle',
    'status.total': 'Total {time}',
    'status.structures': 'Structures {count}',
    'status.containers': 'Containers {count}',
    'status.error': 'Error: ',
    'status.exitTip': 'Stop the backend and close this tool',
    'status.exitConfirm': 'Exit loot-radar? The backend process will stop.',
    'status.exit': 'Exit',

    'map.center': 'Center',
    'map.close': 'Close ✕',
    'map.refCoords': 'Ref {x} {z}',
    'map.unmarkTip': 'Unmark obtained',
    'map.distance': '{count}b',

    'api.disconnected': 'Connection lost, reconnecting…',

    'title.suffix': 'Minecraft Loot Finder',
  },
} as const satisfies Record<Locale, Record<string, string>>

export type MessageKey = keyof (typeof messages)['zh']

const methodLabels: Record<Locale, Record<string, string>> = {
  zh: {
    chest: '箱子',
    archaeology: '考古',
    pot_sherd: '陶罐',
    corridor_pot: '柱廊陶罐',
    vault: '宝库',
    vault_ominous: '不祥宝库',
    entity: '远古守卫者',
  },
  en: {
    chest: 'Chest',
    archaeology: 'Archaeology',
    pot_sherd: 'Pot',
    corridor_pot: 'Corridor Pot',
    vault: 'Vault',
    vault_ominous: 'Ominous Vault',
    entity: 'Elder Guardian',
  },
}

const dimensionLabels: Record<Locale, Record<string, string>> = {
  zh: { overworld: '主世界', the_nether: '下界', the_end: '末地' },
  en: { overworld: 'Overworld', the_nether: 'The Nether', the_end: 'The End' },
}

const sequenceLabels: Record<Locale, Record<string, string>> = {
  zh: { entity: '远古守卫者', vault: '宝库', vault_ominous: '不祥宝库' },
  en: { entity: 'Elder Guardian', vault: 'Vault', vault_ominous: 'Ominous Vault' },
}

const mergeLabels: Record<Locale, Record<string, string>> = {
  zh: { 'minecraft:ocean_ruin': '海底废墟', 'minecraft:shipwreck_all': '沉船' },
  en: { 'minecraft:ocean_ruin': 'Ocean Ruins', 'minecraft:shipwreck_all': 'Shipwreck' },
}

export function methodLabel(locale: Locale, method: string): string {
  return methodLabels[locale][method] ?? method
}

export function dimensionLabel(locale: Locale, dimension: string): string {
  return dimensionLabels[locale][dimension] ?? dimension
}

export function sequenceLabel(locale: Locale, method: string): string | undefined {
  return sequenceLabels[locale][method]
}

export function mergeLabel(locale: Locale, groupKey: string): string | undefined {
  return mergeLabels[locale][groupKey]
}
