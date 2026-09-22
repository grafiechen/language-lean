/** 发布快照及草稿共用的有版本内容契约；数组第一项作为主要项。 */
export interface DictionaryContent {
  schemaVersion: number
  readings: { id: string; reading: string; pronunciationText: string }[]
  senses: { id: string; partOfSpeech: string; gloss: string; examples: {
    id: string; text: string; pronunciationText: string; translation: string
  }[] }[]
  sourceName: string
  license: string
}
/** 列表只展示词条身份、公开状态和待发布标记。 */
export interface EntryRow {
  id: string; languageCode: string; written: string; status: string
  currentRevision: number; hasDraft: boolean
}
/** 后台编辑版本用于阻止陈旧表单覆盖；published 和 draft 明确分开。 */
export interface AdminEntry extends EntryRow {
  scriptCode: string; version: number
  published: DictionaryContent | null; draft: DictionaryContent | null
}
/** 用户看不到草稿；封禁条目的 content 为空。 */
export interface PublicEntry extends EntryRow { scriptCode: string; content: DictionaryContent | null }
/** 所有列表统一使用每页 20 条的服务端分页。 */
export interface Results<T> { items: T[]; total: number; page: number }
/** 不可改写的历史版本。 */
export interface History { revision: number; content: DictionaryContent; publishedBy: string; publishedAt: string; note: string }
/** 管理语言包含禁用状态和并发编辑版本。 */
export interface AdminLanguage { code: string; displayName: string; pronunciationLocale: string; enabled: boolean; version: number }
/** 空草稿预设一个词义，读音可选；每个子项身份使用独立 UUID。 */
export function emptyContent(): DictionaryContent {
  return { schemaVersion: 1, readings: [], senses: [{ id: crypto.randomUUID(), partOfSpeech: '', gloss: '', examples: [] }],
    sourceName: '手工录入', license: '' }
}
/** 状态统一用中文显示，不把内部枚举暴露给用户。 */
export function statusLabel(status: string): string {
  return ({ DRAFT: '草稿', PUBLISHED: '已发布', BANNED: '已封禁' } as Record<string, string>)[status] ?? status
}
