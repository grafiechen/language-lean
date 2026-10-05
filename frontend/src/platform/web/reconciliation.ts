import { ref } from 'vue'
import type { AccountScope } from '../../core/reviews'
import type { LearningItem, Wordbook } from '../../features/learning/types'
import { trainableStatus } from '../../features/learning/types'
import { postJson } from '../../shared/api'
import { accountKey } from './database'
import { currentReviewScope, learningDatabase } from './reviewSync'
import { invalidateOfflinePreparations } from './offlineLearning'
import { invalidatePersonalContentDownloads, purgeDeletedPrivateCache } from './personalContent'

interface Snapshot { userId: string; items: LearningItem[]; missingItemIds: string[];
  books: { book: Wordbook; learningItemIds: string[] }[]; missingBookIds: string[] }
export interface ReconciliationResult { removed: number; invalidated: number }
const running = new Map<string, Promise<ReconciliationResult>>()
/** 提示按账号分区，不混入其他账户刚核对的结果。 */
export const reconciliationMessages = ref<Record<string, string>>({})

/** 返回所有请求身份的明确结论才允许撤销缓存，部分响应或账号变化整体拒绝。 */
function validateSnapshot(snapshot: Snapshot, userId: string, ids: string[], books: string[]) {
  const exact = (requested: string[], returned: string[]) => returned.length === new Set(returned).size
    && returned.length === requested.length && returned.every(id => requested.includes(id))
  if (snapshot.userId !== userId || !exact(ids, [...snapshot.items.map(item => item.id), ...snapshot.missingItemIds])
    || !exact(books, [...snapshot.books.map(row => row.book.id), ...snapshot.missingBookIds]))
    throw new Error('服务器核对结果不完整或账号已变化，本地记录已保留。')
}
/** 同一账户共用核对任务；先完整读取，再以单个 IndexedDB 事务更新。 */
export function reconcileLearning(scope: AccountScope): Promise<ReconciliationResult> {
  const key = accountKey(scope), existing = running.get(key)
  if (existing) return existing
  const task = run(scope).finally(() => running.delete(key))
  running.set(key, task); return task
}
/** 网络期间本机删除/重置或新代际确认变化时重试，不让旧响应恢复进度。 */
async function run(scope: AccountScope): Promise<ReconciliationResult> {
  for (let retry = 0; retry < 3; retry++) {
    try { return await snapshotAndApply(scope) }
    catch (cause) { if (!(cause instanceof CacheChanged) || retry === 2) throw cause }
  }
  throw new Error('缓存正在变化，请稍后重试核对。')
}
class CacheChanged extends Error { constructor() { super('本机缓存已变化，请稍后重试核对。') } }
/** 收集已缓存身份；没有缓存时不查询全词典或其他账号。 */
async function snapshotAndApply(scope: AccountScope): Promise<ReconciliationResult> {
  const key = accountKey(scope)
  const current = await currentReviewScope()
  if (current.serverId !== scope.serverId || current.userId !== scope.userId) throw new Error('请登录当前学习记录所属账号后核对。')
  const local = await learningDatabase.transaction('r', [learningDatabase.pending, learningDatabase.states,
    learningDatabase.resources, learningDatabase.preparations, learningDatabase.syncMeta, learningDatabase.audio], async () => ({
    pending: await learningDatabase.pending.where('accountKey').equals(key).toArray(),
    states: await learningDatabase.states.where('accountKey').equals(key).toArray(),
    resources: await learningDatabase.resources.where('accountKey').equals(key).toArray(),
    preparations: await learningDatabase.preparations.where('accountKey').equals(key).toArray(),
    audio: await learningDatabase.audio.where('accountKey').equals(key).toArray(),
    revision: (await learningDatabase.syncMeta.get(key))?.revision ?? 0,
  }))
  const overrideClips = local.audio.flatMap(row => {
    try { const request = JSON.parse(row.requestKey) as unknown[]
      return request[1] === 'OVERRIDE' && typeof request[0] === 'string' ? [{ itemId: request[0], audioRevision: row.audioRevision ?? 0 }] : []
    } catch { return [] }
  })
  const ids = [...new Set([...local.pending.map(row => row.learningItemId), ...local.states.map(row => row.learningItemId),
    ...local.resources.flatMap(row => Object.keys(row.items)), ...local.preparations.flatMap(row => row.items.map(item => item.id)), ...overrideClips.map(clip => clip.itemId)])]
  const bookIds = [...new Set([...local.resources.map(row => row.wordbookId), ...local.preparations.map(row => row.wordbookId)])]
  if (!ids.length && !bookIds.length) return { removed: 0, invalidated: 0 }
  const items = new Map<string, LearningItem>(), books = new Map<string, Snapshot['books'][number]>()
  const missing = new Set<string>(), missingBooks = new Set<string>()
  for (let offset = 0; offset < Math.max(ids.length, bookIds.length); offset += 200) {
    const selectedIds = ids.slice(offset, offset + 200), selectedBooks = bookIds.slice(offset, offset + 200)
    const snapshot = await postJson<Snapshot>('/api/v1/learning/reconcile', { learningItemIds: selectedIds, wordbookIds: selectedBooks })
    validateSnapshot(snapshot, scope.userId, selectedIds, selectedBooks)
    snapshot.items.forEach(item => items.set(item.id, item)); snapshot.books.forEach(book => books.set(book.book.id, book))
    snapshot.missingItemIds.forEach(id => missing.add(id)); snapshot.missingBookIds.forEach(id => missingBooks.add(id))
  }
  const checked = await currentReviewScope()
  if (checked.userId !== scope.userId || checked.serverId !== scope.serverId) throw new Error('账号已切换，本地记录已保留。')
  const result = await learningDatabase.transaction('rw', [learningDatabase.pending, learningDatabase.states, learningDatabase.drafts,
    learningDatabase.resources, learningDatabase.preparations, learningDatabase.issues, learningDatabase.syncMeta, learningDatabase.projections, learningDatabase.audio], async () => {
    if (((await learningDatabase.syncMeta.get(key))?.revision ?? 0) !== local.revision) throw new CacheChanged()
    const initialStates = new Map(local.states.map(row => [row.learningItemId, row.progressEpoch]))
    for (const state of await learningDatabase.states.where('accountKey').equals(key).toArray())
      if (ids.includes(state.learningItemId) && (!initialStates.has(state.learningItemId)
        || initialStates.get(state.learningItemId) !== state.progressEpoch)) throw new CacheChanged()
    const pendingBefore = await learningDatabase.pending.where('accountKey').equals(key).count()
    const resourcesBefore = await learningDatabase.resources.where('accountKey').equals(key).count()
    const epochs = Object.fromEntries([...items].map(([id, item]) => [id, item.progressEpoch])) as Record<string, string | null>
    missing.forEach(id => { epochs[id] = null })
    const purged = missing.size ? await purgeDeletedPrivateCache(scope, [...missing]) : 0
    const audioInvalidatedBooks = new Set<string>()
    for (const item of items.values()) {
      const previous = [...local.preparations.flatMap(row => row.items), ...local.resources.flatMap(row => Object.values(row.items).map(word => word.learning))]
        .find(old => old.id === item.id && (old.personalAudioRevision ?? 0) !== (item.personalAudioRevision ?? 0))
      if (previous || overrideClips.some(clip => clip.itemId === item.id && clip.audioRevision !== (item.personalAudioRevision ?? 0))) {
        local.preparations.filter(row => row.items.some(old => old.id === item.id)).forEach(row => audioInvalidatedBooks.add(row.wordbookId))
        await invalidatePersonalContentDownloads(scope, item.id, item.personalContentRevision ?? 0, item.personalAudioRevision ?? 0)
      }
    }
    await invalidateOfflinePreparations(scope, epochs)
    let invalidated = purged + audioInvalidatedBooks.size
    for (const row of await learningDatabase.preparations.where('accountKey').equals(key).toArray()) {
      if (missingBooks.has(row.wordbookId)) { await learningDatabase.preparations.delete([key, row.wordbookId]); invalidated++; continue }
      const book = books.get(row.wordbookId)
      const members = book ? new Set(book.learningItemIds) : null
      const observed = new Set(local.preparations.find(value => value.wordbookId === row.wordbookId)?.items.map(item => item.id) ?? [])
      const changed = row.items.some(item => missing.has(item.id) || observed.has(item.id) && members && !members.has(item.id))
        || row.words.some(word => { const item = items.get(word.learning.id); return item && (!trainableStatus(item.status)
          || item.currentRevision !== word.entry.currentRevision || item.progressEpoch !== word.learning.progressEpoch
          || (item.personalContentRevision ?? 0) !== (word.personal?.revision ?? word.learning.personalContentRevision ?? 0)) })
        || !!row.contents?.some(content => { const item = items.get(content.learningItemId); return item && (!trainableStatus(item.status)
          || item.currentRevision !== content.entry.currentRevision || (item.personalContentRevision ?? 0) !== content.personal.revision) })
        || !!book?.learningItemIds.some(id => !row.items.some(item => item.id === id))
      row.items = row.items.filter(item => !missing.has(item.id) && (!observed.has(item.id) || !members || members.has(item.id)))
        .map(item => items.get(item.id) ?? item)
      const retained = new Set(row.items.map(item => item.id))
      if (row.contents) row.contents = row.contents.filter(content => retained.has(content.learningItemId))
      row.words = row.words.filter(word => retained.has(word.learning.id)).map(word => ({ ...word, learning: items.get(word.learning.id) ?? word.learning }))
      if (book) row.book = book.book
      if (changed) { row.status = 'PARTIAL'; row.failures = ['服务器的进度、词条内容或单词本成员已变化，请重新准备离线内容。']; invalidated++ }
      await learningDatabase.preparations.put(row)
    }
    for (const resource of await learningDatabase.resources.where('accountKey').equals(key).toArray()) {
      const members = books.get(resource.wordbookId)?.learningItemIds
      const observed = new Set(Object.keys(local.resources.find(row => row.batchId === resource.batchId)?.items ?? {}))
      if (missingBooks.has(resource.wordbookId) || Object.values(resource.items).some(word => {
        const item = items.get(word.learning.id)
        return missing.has(word.learning.id) || observed.has(word.learning.id) && members && !members.includes(word.learning.id)
          || item && (!trainableStatus(item.status) || item.currentRevision !== word.entry.currentRevision
            || (item.personalAudioRevision ?? 0) !== (word.personal?.audioRevision ?? word.learning.personalAudioRevision ?? 0))
      })) {
        await learningDatabase.resources.delete([key, resource.batchId]); await learningDatabase.drafts.delete([key, resource.batchId])
      }
    }
    for (const item of items.values()) {
      const previous = (await learningDatabase.states.get([key, item.id]))?.payload as LearningItem | undefined
      if (!previous || previous.progressEpoch !== item.progressEpoch || previous.progressVersion === '0'
        || item.progressVersion !== '0' && (Date.parse(item.progressVersion) > Date.parse(previous.progressVersion)
          || Date.parse(item.progressVersion) === Date.parse(previous.progressVersion) && (item.lastReviewEventId ?? '') >= (previous.lastReviewEventId ?? '')))
        await learningDatabase.states.put({ accountKey: key, learningItemId: item.id, progressEpoch: item.progressEpoch, payload: item })
    }
    return { removed: pendingBefore - await learningDatabase.pending.where('accountKey').equals(key).count(),
      invalidated: invalidated + resourcesBefore - await learningDatabase.resources.where('accountKey').equals(key).count() }
  })
  if (result.removed || result.invalidated) reconciliationMessages.value[key] = `服务器信息已核对：清理 ${result.removed} 条已删除或重置的旧进度，${result.invalidated} 个离线范围或训练已失效，请重新准备。`
  return result
}
