import type { AccountScope } from '../../core/reviews'
import type { LearningItem, ReviewReceipt, Wordbook } from '../../features/learning/types'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import { getJson } from '../../shared/api'
import { accountKey, bumpCacheRevision, type OfflinePreparationRow } from './database'
import { currentReviewScope, learningDatabase, pendingReviews } from './reviewSync'
import { invalidateLearningProgress, prepareTraining, type PreparedTraining } from './trainingSessions'
import { requireOfflineShell } from './offlineShell'
import { aggregate } from '../../core/reviews'

/** 离线准备状态按当前服务器和稳定用户 ID 查询。 */
export async function offlinePreparations(scope: AccountScope): Promise<OfflinePreparationRow[]> {
  return learningDatabase.preparations.where('accountKey').equals(accountKey(scope)).toArray()
}
/** 准备下载前后验证在线归属，不能把切换后的账号数据写入旧分区。 */
async function verifyOwner(scope: AccountScope) {
  const current = await currentReviewScope()
  if (current.serverId !== scope.serverId || current.userId !== scope.userId) throw new Error('登录账号已变化，请重新登录学习记录所属账号。')
}
/** 下载整个单词本，保存明确的失败状态；第一次准备必须联网。 */
export async function prepareOfflineWordbook(scope: AccountScope, book: Wordbook,
  progress: (done: number, total: number) => void): Promise<OfflinePreparationRow> {
  await verifyOwner(scope); await requireOfflineShell()
  const key = accountKey(scope)
  const observedRevision = (await learningDatabase.syncMeta.get(key))?.revision ?? 0
  const items = await getJson<LearningItem[]>('/api/v1/learning/wordbooks/' + book.id + '/items')
  const row: OfflinePreparationRow = { accountKey: key, wordbookId: book.id, book: JSON.parse(JSON.stringify(book)) as Wordbook,
    preparedAt: new Date().toISOString(), status: 'PREPARING', words: [], items,
    missingPronunciation: 0, failures: [] }
  await verifyOwner(scope)
  const revision = await learningDatabase.transaction('rw', learningDatabase.preparations, learningDatabase.syncMeta, async () => {
    if (((await learningDatabase.syncMeta.get(key))?.revision ?? 0) !== observedRevision)
      throw new Error('学习状态已变化，请重新准备离线内容。')
    await bumpCacheRevision(learningDatabase, scope)
    await learningDatabase.preparations.put(row)
    return (await learningDatabase.syncMeta.get(key))!.revision
  })
  /** 下载不能覆盖期间发生的重置、删除或另一批准备；失败状态也遵守同一校验。 */
  async function saveIfCurrent(): Promise<boolean> {
    return learningDatabase.transaction('rw', learningDatabase.preparations, learningDatabase.syncMeta, async () => {
      if ((await learningDatabase.syncMeta.get(key))?.revision !== revision) return false
      await bumpCacheRevision(learningDatabase, scope)
      await learningDatabase.preparations.put(row)
      return true
    })
  }
  try {
    const result = await prepareTraining(scope, book.id, true, progress, { allReadings: true })
    await verifyOwner(scope)
    Object.assign(row, result, { status: result.failures.length ? 'PARTIAL' : result.words.length ? 'READY' : 'EMPTY' })
    if (!await saveIfCurrent()) throw new Error('学习状态已变化，请重新准备离线内容。')
    return row
  } catch (cause) {
    row.status = 'PARTIAL'; row.failures = [cause instanceof Error ? cause.message : '离线准备失败，请联网重试。']
    await saveIfCurrent()
    throw cause
  }
}
/** 合并已确认状态及同代际本地完成事件，使再次离线训练引用正确的基准事件。 */
export async function localLearningItem(scope: AccountScope, item: LearningItem): Promise<LearningItem> {
  const value = { ...item }
  delete value.pendingSchedule
  const newer = (version: string, eventId: string | null) => value.progressVersion === '0'
    || Date.parse(version) > Date.parse(value.progressVersion)
    || Date.parse(version) === Date.parse(value.progressVersion) && (eventId ?? '') >= (value.lastReviewEventId ?? '')
  const state = await learningDatabase.states.get([accountKey(scope), item.id])
  const receipt = state?.payload as ReviewReceipt | undefined
  if (receipt?.progressEpoch === item.progressEpoch && newer(receipt.progressVersion, receipt.lastReviewEventId)) Object.assign(value, {
    progressVersion: receipt.progressVersion, lastReviewEventId: receipt.lastReviewEventId,
    lastReviewedAt: receipt.lastReviewedAt, nextReviewAt: receipt.nextReviewAt,
    reviewCount: receipt.reviewCount, lapseCount: receipt.lapseCount, automaticEarFocus: receipt.automaticEarFocus,
    fsrsState: receipt.fsrsState,
  })
  const pendingEvents = (await pendingReviews.list(scope)).filter(event => event.learningItemId === item.id
    && event.progressEpoch === item.progressEpoch)
  const pending = pendingEvents.sort((a, b) => Date.parse(b.completedAt) - Date.parse(a.completedAt)
      || b.eventId.localeCompare(a.eventId))[0]
  if (pending && newer(pending.completedAt, pending.eventId)) {
    value.progressVersion = pending.completedAt; value.lastReviewEventId = pending.eventId
    value.lastReviewedAt = pending.completedAt
    const projection = await learningDatabase.projections.get([accountKey(scope), pending.eventId])
    if (projection?.progressEpoch === item.progressEpoch && projection.learningItemId === item.id) {
      value.fsrsState = projection.fsrsState; value.scheduler = projection.scheduler; value.nextReviewAt = projection.nextReviewAt
      value.pendingSchedule = 'PROJECTED'; value.due = Date.parse(projection.nextReviewAt) <= Date.now()
    } else {
      // 缺少固定算法参数的旧缓存不猜测周期，也不能用旧卡片计算新事件的依赖链。
      value.fsrsState = undefined; value.nextReviewAt = null; value.pendingSchedule = 'UNAVAILABLE'; value.due = false
    }
  } else value.due = !value.nextReviewAt || Date.parse(value.nextReviewAt) <= Date.now()
  value.reviewCount += pendingEvents.length
  value.lapseCount += pendingEvents.filter(event => aggregate(['LISTEN_RECALL'], event.results) === 'AGAIN').length
  value.automaticEarFocus ||= pendingEvents.some(event => ['AGAIN', 'HARD'].includes(aggregate(['LISTEN_RECALL'], event.results) ?? ''))
  return value
}
/** 离线开新批次只取已准备范围，逐项检查音频，不调用 TTS 或个人 API。 */
export async function prepareCachedTraining(scope: AccountScope, bookId: string, extra: boolean, earFocus = false): Promise<PreparedTraining> {
  const revision = (await learningDatabase.syncMeta.get(accountKey(scope)))?.revision ?? 0
  const row = await learningDatabase.preparations.get([accountKey(scope), bookId])
  if (!row || !['READY', 'EMPTY'].includes(row.status)) throw new Error('此单词本尚未完整准备，请联网准备后再训练。')
  const result: PreparedTraining = { words: [], missingPronunciation: row.missingPronunciation, failures: [] }
  for (const word of row.words) {
    const learning = await localLearningItem(scope, word.learning)
    if (earFocus && !learning.manualEarFocus && !learning.automaticEarFocus) continue
    if (!extra && !learning.due) continue
    const versions = earFocus ? word.readingAudio ?? [{ audioVersionId: word.audioVersionId, textHash: word.textHash }] : [{ audioVersionId: word.audioVersionId, textHash: word.textHash }]
    let valid = true
    for (const version of versions) {
      const audio = await learningDatabase.audio.get([accountKey(scope), version.audioVersionId])
      if (!audio?.blob.size || audio.textHash !== version.textHash) valid = false
    }
    if (!valid) result.failures.push(word.entry.written + '：音频缓存缺失，请联网重新准备。')
    else result.words.push({ ...word, learning, cacheRevision: revision } as TrainingWord)
  }
  result.words.sort((a, b) => Number(a.learning.nextReviewAt === null) - Number(b.learning.nextReviewAt === null)
    || Date.parse(a.learning.nextReviewAt ?? '9999-12-31') - Date.parse(b.learning.nextReviewAt ?? '9999-12-31')
    || a.learning.id.localeCompare(b.learning.id))
  if (((await learningDatabase.syncMeta.get(accountKey(scope)))?.revision ?? 0) !== revision)
    throw new Error('读取期间学习缓存已变化，请重新准备。')
  return result
}
/** 重置和最后关联删除同时使离线准备快照失效，不允许旧快照创建新进度。 */
export async function invalidateOfflinePreparations(scope: AccountScope, epochs: Record<string, string | null>): Promise<void> {
  await learningDatabase.transaction('rw', [learningDatabase.pending, learningDatabase.drafts, learningDatabase.resources,
    learningDatabase.states, learningDatabase.preparations, learningDatabase.issues, learningDatabase.syncMeta, learningDatabase.projections], async () => {
    await invalidateLearningProgress(scope, epochs)
    for (const row of await offlinePreparations(scope)) {
      if (row.items.some(item => Object.hasOwn(epochs, item.id) && epochs[item.id] !== item.progressEpoch)) {
        row.status = 'PARTIAL'; row.failures = ['学习进度已删除或重置，请联网重新准备此单词本。']
        await learningDatabase.preparations.put(row)
      }
    }
  })
}
/** 手动重点立即写入本人各本缓存，仅更新同代次标记，不覆盖FSRS和未上传进度。 */
export async function cacheManualEarFocus(scope: AccountScope, item: LearningItem): Promise<void> {
  const key = accountKey(scope)
  await learningDatabase.transaction('rw', learningDatabase.preparations, learningDatabase.resources, learningDatabase.syncMeta, async () => {
    await bumpCacheRevision(learningDatabase, scope)
    const update = (cached: LearningItem) => {
      if (cached.id === item.id && cached.progressEpoch === item.progressEpoch) cached.manualEarFocus = item.manualEarFocus
    }
    for (const row of await learningDatabase.preparations.where('accountKey').equals(key).toArray()) {
      row.items.forEach(update); row.words.forEach(word => update(word.learning))
      await learningDatabase.preparations.put(row)
    }
    for (const row of await learningDatabase.resources.where('accountKey').equals(key).toArray()) {
      Object.values(row.items).forEach(word => update(word.learning))
      await learningDatabase.resources.put(row)
    }
  })
}

/** 单词本删除或成员移除只撤销该分类的缓存；仍有其他引用的共享事件保留。 */
export async function reconcileOfflineMembership(scope: AccountScope, bookId: string, items: LearningItem[] | null): Promise<void> {
  // 页面可能传入Vue响应式代理，IndexedDB只能保存独立的JSON领域快照。
  const snapshot = items === null ? null : JSON.parse(JSON.stringify(items)) as LearningItem[]
  const key = accountKey(scope), ids = new Set(snapshot?.map(item => item.id) ?? [])
  await learningDatabase.transaction('rw', learningDatabase.preparations, learningDatabase.resources, learningDatabase.drafts, learningDatabase.syncMeta, async () => {
    await bumpCacheRevision(learningDatabase, scope)
    const row = await learningDatabase.preparations.get([key, bookId])
    if (!snapshot) await learningDatabase.preparations.delete([key, bookId])
    else if (row) {
      const previousIds = new Set(row.items.map(item => item.id))
      if (snapshot.some(item => !previousIds.has(item.id))) {
        row.status = 'PARTIAL'
        row.failures = ['单词本新增了尚未下载的词条，请联网重新准备离线内容。']
      }
      row.words = row.words.filter(word => ids.has(word.learning.id)); row.items = snapshot
      if (row.contents) row.contents = row.contents.filter(content => ids.has(content.learningItemId))
      row.book.itemCount = snapshot.length
      await learningDatabase.preparations.put(row)
    }
    const resources = await learningDatabase.resources.where('accountKey').equals(key).filter(resource => resource.wordbookId === bookId).toArray()
    for (const resource of resources) {
      if (!snapshot || Object.values(resource.items).some(word => !ids.has(word.learning.id))) {
        await learningDatabase.resources.delete([key, resource.batchId]); await learningDatabase.drafts.delete([key, resource.batchId])
      }
    }
  })
}
