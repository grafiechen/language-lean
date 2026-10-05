import type { AccountScope } from '../../core/reviews'
import { currentTrainingQuestion } from '../../core/training'
import { accountKey, bumpCacheRevision } from './database'
import { learningDatabase } from './reviewSync'

/** 账号的可下载内容与不可静默丢弃的学习记录分别计数。 */
export interface AccountCacheUsage {
  audioBytes: number; contentBytes: number; audioCount: number; unusedAudioBytes: number; unusedAudioCount: number
  preparations: number; pending: number; issues: number; drafts: number; projections: number
  continuing: { wordbookId: string; name: string }[]
}
/** 浏览器额度是整个站点的估算，与单个账号的内容字节数不是同一口径。 */
export interface SiteStorageUsage { usage?: number; quota?: number; persisted?: boolean; persistenceAvailable: boolean }
/** 清理结果只涉及下载，不能把它当作复习提交确认。 */
export interface CacheCleanupResult { removedBooks: number; removedAudio: number; freedAudioBytes: number }

/** 提供稳定的可读字节显示，不将未知浏览器额度误显示为零。 */
export function formatBytes(bytes: number | undefined): string {
  if (bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return '无法估算'
  const units = ['B', 'KB', 'MB', 'GB'], index = Math.min(3, Math.floor(Math.log2(Math.max(bytes, 1)) / 10))
  return `${(bytes / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}
/** 兼容普通 IndexedDB 错误及 Dexie 包装的配额异常，给用户一个可执行的恢复入口。 */
export function learningStorageError(cause: unknown, fallback: string): string {
  let value = cause as { name?: string; inner?: unknown; cause?: unknown } | null
  for (let depth = 0; value && depth < 6; depth++) {
    if (value.name === 'QuotaExceededError') return '本机存储空间不足。请到“离线准备 → 本机缓存”清理未引用音频，再重试；本次操作未保存的部分不会计入复习。'
    value = (value.inner ?? value.cause) as typeof value
  }
  return cause instanceof Error ? cause.message : fallback
}
/** 私密数据只在 IndexedDB 中按账号统计，不读取其他账号的内容来显示总计。 */
export async function accountCacheUsage(scope: AccountScope): Promise<AccountCacheUsage> {
  const db = learningDatabase, key = accountKey(scope)
  return db.transaction('r', [db.audio, db.preparations, db.resources, db.drafts, db.pending, db.issues, db.projections], async () => {
    const preparations = await db.preparations.where('accountKey').equals(key).toArray()
    const resources = await db.resources.where('accountKey').equals(key).toArray()
    const drafts = await db.drafts.where('accountKey').equals(key).toArray()
    const audio = await db.audio.where('accountKey').equals(key).toArray()
    const referenced = new Set([...preparations.flatMap(row => row.words.flatMap(word => [word.audioVersionId, ...(word.readingAudio?.map(audio => audio.audioVersionId) ?? [])])),
      ...resources.flatMap(row => Object.values(row.items).flatMap(word => [word.audioVersionId, ...(word.readingAudio?.map(audio => audio.audioVersionId) ?? [])]))])
    const unused = audio.filter(row => !referenced.has(row.audioVersionId))
    const active = new Set(drafts.filter(row => currentTrainingQuestion(row.payload)).map(row => row.batchId))
    const bookIds = [...new Set(resources.filter(row => active.has(row.batchId)).map(row => row.wordbookId))]
    return { audioBytes: audio.reduce((sum, row) => sum + row.blob.size, 0), audioCount: audio.length,
      contentBytes: preparations.length || resources.length ? new TextEncoder().encode(JSON.stringify({ preparations, resources })).length : 0,
      unusedAudioBytes: unused.reduce((sum, row) => sum + row.blob.size, 0), unusedAudioCount: unused.length,
      preparations: preparations.length, drafts: drafts.length,
      pending: await db.pending.where('accountKey').equals(key).count(), issues: await db.issues.where('accountKey').equals(key).count(),
      projections: await db.projections.where('accountKey').equals(key).count(),
      continuing: bookIds.map(wordbookId => ({ wordbookId, name: preparations.find(row => row.wordbookId === wordbookId)?.book.name ?? '未完成训练' })) }
  })
}
/** 缺少 Storage API 或浏览器拒绝估算时仍允许使用账号级统计和清理。 */
export async function siteStorageUsage(storage = globalThis.navigator?.storage): Promise<SiteStorageUsage> {
  const result: SiteStorageUsage = { persistenceAvailable: typeof storage?.persist === 'function' }
  try { const estimate = await storage?.estimate?.(); result.usage = estimate?.usage; result.quota = estimate?.quota } catch { /* 可选能力 */ }
  try { result.persisted = await storage?.persisted?.() } catch { /* 可选能力 */ }
  return result
}
/** 只有用户点击后才请求浏览器保留，不把请求成功当成永久不会丢失的承诺。 */
export async function requestStoragePersistence(storage = globalThis.navigator?.storage): Promise<boolean> {
  if (!storage?.persist) throw new Error('当前浏览器不支持申请保留离线数据。')
  return storage.persist()
}
/** 一个事务中重算全部引用再清理；下载、训练开始和清理通过缓存修订隔离。 */
async function cleanup(scope: AccountScope, mode: 'UNUSED' | 'BOOK' | 'ACCOUNT', bookId?: string): Promise<CacheCleanupResult> {
  const db = learningDatabase, key = accountKey(scope)
  return db.transaction('rw', [db.audio, db.preparations, db.resources, db.syncMeta], async () => {
    let removedBooks = 0
    if (mode === 'BOOK') {
      if (!bookId) throw new Error('请选择要移除下载的单词本。')
      if (await db.preparations.get([key, bookId])) { await db.preparations.delete([key, bookId]); removedBooks = 1 }
    } else if (mode === 'ACCOUNT') removedBooks = await db.preparations.where('accountKey').equals(key).delete()
    const preparations = await db.preparations.where('accountKey').equals(key).toArray()
    const resources = await db.resources.where('accountKey').equals(key).toArray()
    const referenced = new Set([...preparations.flatMap(row => row.words.flatMap(word => [word.audioVersionId, ...(word.readingAudio?.map(audio => audio.audioVersionId) ?? [])])),
      ...resources.flatMap(row => Object.values(row.items).flatMap(word => [word.audioVersionId, ...(word.readingAudio?.map(audio => audio.audioVersionId) ?? [])]))])
    const unused = (await db.audio.where('accountKey').equals(key).toArray()).filter(row => !referenced.has(row.audioVersionId))
    await db.audio.bulkDelete(unused.map(row => [key, row.audioVersionId]))
    // 先释放可删除内容，再写少量修订信息；两者仍在同一事务，避免额度满时先分配新空间。
    if (mode !== 'UNUSED' || unused.length) await bumpCacheRevision(db, scope)
    return { removedBooks, removedAudio: unused.length, freedAudioBytes: unused.reduce((sum, row) => sum + row.blob.size, 0) }
  })
}
/** 删除未被任何单词本或训练快照引用的音频，所有复习记录保持原样。 */
export function clearUnusedAudio(scope: AccountScope) { return cleanup(scope, 'UNUSED') }
/** 移除一个单词本的离线准备；共享音频和训练草稿继续保留。 */
export function removeOfflineDownload(scope: AccountScope, bookId: string) { return cleanup(scope, 'BOOK', bookId) }
/** 清理当前账号的下载范围；不删除待上传事件、账号身份或进行中的训练。 */
export function clearAccountDownloads(scope: AccountScope) { return cleanup(scope, 'ACCOUNT') }
