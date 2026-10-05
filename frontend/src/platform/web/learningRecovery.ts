import type { AccountScope, CompletedReview } from '../../core/reviews'
import { currentTrainingQuestion } from '../../core/training'
import type { LearningItem, Wordbook } from '../../features/learning/types'
import { trainableStatus } from '../../features/learning/types'
import type { LearningContent } from '../../features/learning/personalContent'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import { getJson, postJson } from '../../shared/api'
import { accountKey, bumpCacheRevision, requireLiveCache, type DraftRow, type TrainingResourceRow } from './database'
import { learningDatabase as db, currentReviewScope } from './reviewSync'
import { pronunciationHash } from './audio'
import { object, uuid, parseRecoveryBackup, sameReview, type RecoveryBackup } from './learningRecoveryFormat'

interface Check {
  state: { userId: string; items: LearningItem[]; missingItemIds: string[];
    books: { book: Wordbook; learningItemIds: string[] }[]; missingBookIds: string[] }
  events: { eventId: string; status: 'MISSING' | 'ACCEPTED' | 'CONFLICT'; submission: CompletedReview | null }[]
}
/** 预检只展示统计，实际写入数据留在模块内部，页面不能修改待恢复正文。 */
export interface RecoveryPlan {
  pending: number; drafts: number; accepted: number; existing: number; deletedOrReset: number;
  conflicts: number; skippedDrafts: number; baselineMissing: number; warnings: readonly string[]
}
interface Prepared { plan: RecoveryPlan; pending: CompletedReview[]; drafts: DraftRow[]; resources: TrainingResourceRow[]; items: LearningItem[]; revision: number }
const trusted = new WeakMap<RecoveryPlan, { scope: AccountScope; backup: RecoveryBackup }>()
/** 预检与确认前后均验证真实会话，文件里的用户名不作为账号身份。 */
async function assertOwner(scope: AccountScope) {
  const current = await currentReviewScope()
  if (current.serverId !== scope.serverId || current.userId !== scope.userId) throw new Error('登录账号已变化，请重新登录备份所属账号。')
}
function exact(requested: string[], returned: string[]) {
  return requested.length === returned.length && new Set(returned).size === returned.length && returned.every(id => requested.includes(id))
}
/** 200 条一组只读核对；任何分组响应不完整时不进行本地写入。 */
async function serverCheck(scope: AccountScope, backup: RecoveryBackup) {
  const ids = [...new Set([...backup.pending.map(event => event.learningItemId), ...backup.drafts.flatMap(row => Object.keys(row.payload.items))])]
  const books = [...new Set(backup.resources.filter(row => backup.drafts.some(draft => draft.batchId === row.batchId)).map(row => row.wordbookId))]
  const events = [...new Set([...backup.pending.map(event => event.eventId), ...backup.pending.flatMap(event => event.baseEventId ? [event.baseEventId] : []),
    ...backup.drafts.flatMap(row => Object.values(row.baselines ?? {}).flatMap(base => base.lastReviewEventId ? [base.lastReviewEventId] : []))])]
  const result = { items: new Map<string, LearningItem>(), books: new Map<string, Check['state']['books'][number]>(), events: new Map<string, Check['events'][number]>() }
  for (let offset = 0; offset < Math.max(ids.length, books.length, events.length, 1); offset += 200) {
    const wanted = { accountId: scope.userId, learningItemIds: ids.slice(offset, offset + 200), wordbookIds: books.slice(offset, offset + 200), eventIds: events.slice(offset, offset + 200) }
    const value = await postJson<Check>('/api/v1/learning/recovery-check', wanted)
    if (value.state.userId !== scope.userId || !exact(wanted.learningItemIds, [...value.state.items.map(item => item.id), ...value.state.missingItemIds])
      || !exact(wanted.wordbookIds, [...value.state.books.map(row => row.book.id), ...value.state.missingBookIds])
      || !exact(wanted.eventIds, value.events.map(event => event.eventId)) || value.events.some(event => !['MISSING', 'ACCEPTED', 'CONFLICT'].includes(event.status)
        || event.status === 'ACCEPTED' && (!event.submission || event.submission.eventId !== event.eventId)))
      throw new Error('服务器恢复核对结果不完整，未写入任何数据。')
    value.state.items.forEach(item => result.items.set(item.id, item)); value.state.books.forEach(row => result.books.set(row.book.id, row))
    value.events.forEach(event => result.events.set(event.eventId, event))
  }
  return result
}
/** 同代次事件可补回；最新服务器进度与已有本机记录不被旧备份覆盖。 */
async function prepare(scope: AccountScope, backup: RecoveryBackup): Promise<Prepared> {
  await assertOwner(scope); await requireLiveCache(db, scope)
  const key = accountKey(scope)
  const local = await db.transaction('r', db.pending, db.drafts, db.resources, db.syncMeta, async () => ({
    revision: (await db.syncMeta.get(key))?.revision ?? 0,
    pending: await db.pending.where('accountKey').equals(key).toArray(),
    drafts: await db.drafts.where('accountKey').equals(key).toArray(), resources: await db.resources.where('accountKey').equals(key).toArray(),
  }))
  const current = await serverCheck(scope, backup)
  const plan = { pending: 0, drafts: 0, accepted: 0, existing: 0, deletedOrReset: 0, conflicts: 0, skippedDrafts: 0, baselineMissing: 0, warnings: [] as string[] }
  const pending: CompletedReview[] = [], accepted = new Set<string>(), conflicts = new Set<string>()
  for (const event of backup.pending) {
    const item = current.items.get(event.learningItemId), known = current.events.get(event.eventId), localEvent = local.pending.find(row => row.eventId === event.eventId)
    if (!item || item.progressEpoch !== event.progressEpoch) { plan.deletedOrReset++; continue }
    if (known?.status === 'ACCEPTED') {
      if (sameReview(event, known.submission!)) { plan.accepted++; accepted.add(event.eventId) }
      else { plan.conflicts++; conflicts.add(event.eventId) }
      continue
    }
    if (known?.status === 'CONFLICT' || localEvent && !sameReview(localEvent, event)
      || local.pending.some(row => row.attemptId === event.attemptId && row.eventId !== event.eventId)) {
      plan.conflicts++; conflicts.add(event.eventId); continue
    }
    if (localEvent) { plan.existing++; continue }
    if (Date.parse(event.completedAt) > Date.now() + 300_000) { plan.conflicts++; conflicts.add(event.eventId); continue }
    pending.push(event)
  }
  // 基准链缺失只标为待处理，不修改原答题时间/事件标识来强行覆盖新进度。
  const available = new Map([...local.pending, ...pending].map(event => [event.eventId, event]))
  for (const event of pending) if (event.baseEventId) {
    const base = available.get(event.baseEventId) ?? current.events.get(event.baseEventId)?.submission
    if (!base || base.learningItemId !== event.learningItemId || base.progressEpoch !== event.progressEpoch
      || Date.parse(base.completedAt) !== Date.parse(event.baseVersion)) plan.baselineMissing++
  }
  const drafts: DraftRow[] = [], resources: TrainingResourceRow[] = []
  const content = new Map<string, LearningContent>()
  for (const raw of backup.drafts) {
    const savedResource = backup.resources.find(row => row.batchId === raw.batchId)!, book = current.books.get(savedResource.wordbookId)
    if (local.drafts.some(row => row.batchId === raw.batchId) || local.resources.some(row => row.batchId === raw.batchId
      || row.wordbookId === savedResource.wordbookId && local.drafts.some(draft => draft.batchId === row.batchId))) { plan.skippedDrafts++; continue }
    const draft = structuredClone(raw), words: Record<string, TrainingWord> = {}
    let valid = !!book
    for (const id of Object.keys(draft.payload.items)) {
      const base = draft.baselines![id], item = current.items.get(id), completedEvent = draft.events?.[id]
      if (!item || item.progressEpoch !== base.progressEpoch || !book?.learningItemIds.includes(id)
        || !trainableStatus(item.status) || completedEvent && conflicts.has(completedEvent)) { valid = false; break }
      if (completedEvent && accepted.has(completedEvent)) {
        delete draft.payload.items[id]; delete draft.baselines![id]; delete draft.events![id]; continue
      }
      // 完成事件缺失/冲突时不能恢复整份草稿；有效事件独立恢复，不丢其他词的答题。
      if (completedEvent && !available.has(completedEvent)) { valid = false; break }
      const original = object(savedResource.items[id]), originalLearning = object(original.learning), originalEntry = object(original.entry)
      if (originalLearning.id !== id || originalLearning.progressEpoch !== base.progressEpoch || originalEntry.id !== (item.dictionaryEntryId ?? item.personalCustomEntryId)
        || originalEntry.currentRevision !== item.currentRevision
        || (object(original.personal ?? {}).revision ?? originalLearning.personalContentRevision ?? 0) !== (item.personalContentRevision ?? 0)) { valid = false; break }
      if (!content.has(id)) content.set(id, await getJson<LearningContent>('/api/v1/learning/items/' + id + '/content'))
      const fresh = content.get(id)!
      if (fresh.learningItemId !== id || fresh.entry.id !== originalEntry.id || fresh.entry.currentRevision !== item.currentRevision
        || fresh.personal.revision !== (item.personalContentRevision ?? 0) || !fresh.entry.content || !trainableStatus(fresh.entry.status)) { valid = false; break }
      const readingId = uuid(original.readingId), reading = fresh.entry.content.readings.find(row => row.id === readingId)
      if (!reading?.pronunciationText.trim() || original.textHash !== await pronunciationHash(reading.pronunciationText)) { valid = false; break }
      const alternatives: NonNullable<TrainingWord['readingAudio']> = []
      if (original.readingAudio != null) {
        if (!Array.isArray(original.readingAudio) || original.readingAudio.length > 100) throw new Error('备份音频版本格式无效。')
        for (const rawClip of original.readingAudio) {
          const clip = object(rawClip), pronunciation = fresh.entry.content.readings.find(row => row.id === uuid(clip.readingId))
          if (!pronunciation?.pronunciationText.trim() || clip.textHash !== await pronunciationHash(pronunciation.pronunciationText)) { valid = false; break }
          alternatives.push({ readingId: pronunciation.id, audioVersionId: uuid(clip.audioVersionId), textHash: String(clip.textHash) })
        }
        if (new Set(alternatives.map(clip => clip.readingId)).size !== alternatives.length) valid = false
      }
      words[id] = { learning: item, entry: fresh.entry, personal: fresh.personal, readingId,
        audioVersionId: uuid(original.audioVersionId), textHash: String(original.textHash), ...(alternatives.length ? { readingAudio: alternatives } : {}) }
      // 只有当前基准匹配时使用服务器 FSRS 配置，旧文件的投影及配置不写入。
      delete base.fsrsState; delete base.scheduler
      if (base.progressVersion === item.progressVersion && (base.lastReviewEventId ?? null) === item.lastReviewEventId) {
        base.fsrsState = item.fsrsState; base.scheduler = item.scheduler
      }
    }
    if (!valid || !currentTrainingQuestion(draft.payload) && !Object.keys(draft.events ?? {}).length) { plan.skippedDrafts++; continue }
    drafts.push(draft); resources.push({ accountKey: key, batchId: draft.batchId, wordbookId: savedResource.wordbookId,
      createdAt: savedResource.createdAt, items: words })
  }
  plan.pending = pending.length; plan.drafts = drafts.length
  if (plan.drafts) plan.warnings.push('备份没有音频文件，继续训练前请联网重新准备当前读音。')
  if (plan.baselineMissing) plan.warnings.push(`${plan.baselineMissing} 条记录缺少基准事件，恢复后仍需补齐基准才能上传。`)
  if (plan.conflicts) plan.warnings.push('冲突或未来时间异常的记录已排除，保留原文件用于核对。')
  await assertOwner(scope)
  return { plan: Object.freeze(plan), pending, drafts, resources, items: [...current.items.values()], revision: local.revision }
}
/** 预检不写入或上传；保留解析后的副本，确认不依赖页面上可修改的文件正文。 */
export async function previewLearningRecovery(scope: AccountScope, text: string): Promise<RecoveryPlan> {
  const backup = parseRecoveryBackup(text, scope), prepared = await prepare(scope, backup)
  trusted.set(prepared.plan, { scope: { ...scope }, backup }); return prepared.plan
}
/** 确认重新核对远端与本机；单事务补充缺失记录，存储失败整笔回滚。 */
export async function applyLearningRecovery(plan: RecoveryPlan): Promise<RecoveryPlan> {
  const source = trusted.get(plan)
  if (!source) throw new Error('恢复预检已失效，请重新选择备份文件。')
  const { scope, backup } = source, prepared = await prepare(scope, backup), key = accountKey(scope)
  if (JSON.stringify(prepared.plan) !== JSON.stringify(plan)) throw new Error('服务器或本机记录已变化，请重新预检后确认恢复。')
  if (!prepared.pending.length && !prepared.drafts.length) { trusted.delete(plan); return prepared.plan }
  await db.transaction('rw', db.pending, db.drafts, db.resources, db.states, db.syncMeta, async () => {
    await requireLiveCache(db, scope)
    if (((await db.syncMeta.get(key))?.revision ?? 0) !== prepared.revision) throw new Error('恢复期间缓存已变化，请重新预检。')
    for (const event of prepared.pending) {
      const existing = await db.pending.get([key, event.eventId])
      if (existing) throw new Error('恢复期间答题记录已变化，请重新预检。')
    }
    for (const draft of prepared.drafts) if (await db.drafts.get([key, draft.batchId]) || await db.resources.get([key, draft.batchId]))
      throw new Error('恢复期间训练草稿已变化，请重新预检。')
    // 直接 add 防止覆盖；服务端接受状态只决定导入，不清除当前本机已有记录。
    for (const event of prepared.pending) await db.pending.add({ ...event, accountKey: key })
    await db.drafts.bulkAdd(prepared.drafts); await db.resources.bulkAdd(prepared.resources)
    const restoredIds = new Set([...prepared.pending.map(event => event.learningItemId), ...prepared.drafts.flatMap(draft => Object.keys(draft.payload.items))])
    for (const item of prepared.items.filter(item => restoredIds.has(item.id))) {
      const previous = await db.states.get([key, item.id])
      if (!previous) await db.states.add({ accountKey: key, learningItemId: item.id, progressEpoch: item.progressEpoch, payload: item })
    }
    if (prepared.pending.length || prepared.drafts.length) await bumpCacheRevision(db, scope)
  })
  trusted.delete(plan); return prepared.plan
}
