import { aggregate, type AccountScope, type CompletedReview, type ReviewTypeResult } from '../../core/reviews'
import { currentTrainingQuestion, type TrainingBatch } from '../../core/training'
import { accountKey, type DraftRow, type TrainingResourceRow } from './database'

export const MAX_BACKUP_BYTES = 20_000_000
/** 当前版本仅允许恢复原始答题和训练关联；其他备份表不进入写入计划。 */
export interface RecoveryBackup { pending: CompletedReview[]; drafts: DraftRow[]; resources: TrainingResourceRow[] }
type ObjectValue = Record<string, unknown>
const fail = (): never => { throw new Error('备份格式或训练记录不完整，未恢复任何数据。') }
/** 在访问未知文件字段之前确认其为普通 JSON 对象。 */
export function object(value: unknown): ObjectValue {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail()
  return value as ObjectValue
}
/** 数据库身份不接受展示名或任意映射键，避免跨身份/原型字段混用。 */
export function uuid(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value)) return fail()
  return value
}
/** 时间限制到 UTC 毫秒精度，并拒绝 Date 自动纠正的非法日历日期。 */
export function time(value: unknown): string {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(value) || !Number.isFinite(Date.parse(value))) return fail()
  const normalized = new Date(value).toISOString()
  if (normalized.slice(0, 19) !== value.slice(0, 19)) return fail()
  return value
}
function array(value: unknown, limit: number): unknown[] {
  if (!Array.isArray(value) || value.length > limit) return fail()
  return value
}
function unique<T>(rows: T[], key: (value: T) => string): void {
  if (new Set(rows.map(key)).size !== rows.length) fail()
}
/** 只接受目前已实现题型；未来版本须增加适配器，不能把未知评分当作完成。 */
function results(value: unknown): ReviewTypeResult[] {
  return array(value, 1).map(raw => {
    const row = object(raw)
    if (row.typeId !== 'LISTEN_RECALL' || row.contractVersion !== 1) return fail()
    const trials = array(row.trials, 1000).map(rawTrial => {
      const trial = object(rawTrial), rating = trial.rating
      if (!['AGAIN', 'HARD', 'GOOD'].includes(String(rating))) return fail()
      return { id: uuid(trial.id), ratedAt: time(trial.ratedAt), rating } as ReviewTypeResult['trials'][number]
    })
    if (!trials.length) return fail()
    return { typeId: 'LISTEN_RECALL', contractVersion: 1, trials }
  })
}
/** 校验并保留原事件和时间字符串，防止改写版本文本后破坏服务端幂等正文比较。 */
export function parseReview(raw: unknown): CompletedReview {
  const row = object(raw), parsed = {
    eventId: uuid(row.eventId), attemptId: uuid(row.attemptId), learningItemId: uuid(row.learningItemId),
    progressEpoch: uuid(row.progressEpoch), baseVersion: row.baseVersion === '0' ? '0' : time(row.baseVersion),
    baseEventId: row.baseEventId == null ? null : uuid(row.baseEventId), submissionVersion: time(row.submissionVersion),
    completedAt: time(row.completedAt), results: results(row.results),
  }
  if (aggregate(['LISTEN_RECALL'], parsed.results) === null || !parsed.results.length
    || Date.parse(parsed.completedAt) !== Date.parse(parsed.submissionVersion)
    || Date.parse(parsed.completedAt) !== Date.parse(parsed.results[0].trials.at(-1)!.ratedAt)
    || parsed.results[0].trials.some(trial => Date.parse(trial.ratedAt) > Date.parse(parsed.completedAt)
      || parsed.baseVersion !== '0' && Date.parse(trial.ratedAt) < Date.parse(parsed.baseVersion))
    || parsed.baseVersion === '0' && parsed.baseEventId !== null) return fail()
  return parsed
}
export function sameReview(a: CompletedReview, b: CompletedReview): boolean {
  // Java Instant 可省略零毫秒；版本字段是原始字符串，必须保持完全一致。
  const canonical = (raw: CompletedReview) => {
    const event = parseReview(raw)
    return { ...event, completedAt: new Date(event.completedAt).toISOString(), results: event.results.map(result => ({ ...result,
      trials: result.trials.map(trial => ({ ...trial, ratedAt: new Date(trial.ratedAt).toISOString() })) })) }
  }
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b))
}
/** 校验仍在使用的队列和事件关联；已获确认而移出的词可保留在分组的 passed 中。 */
function validateDraft(row: DraftRow, pending: Map<string, CompletedReview>): void {
  const batch = row.payload, types = array(batch.orderedReviewTypes, 1)
  if (batch.schemaVersion !== 1 || types.length !== 1 || object(types[0]).typeId !== 'LISTEN_RECALL'
    || object(types[0]).contractVersion !== 1 || row.batchId !== batch.batchId
    || batch.trainingMode && !['REVIEW', 'EXTRA', 'EAR'].includes(batch.trainingMode)) fail()
  uuid(batch.batchId); const groups = array(batch.groups, 1000), allIds: string[] = [], groupIds: string[] = []
  const progress = object(batch.items), baselines = object(row.baselines), eventLinks = object(row.events ?? {})
  let firstActive = groups.length
  for (let index = 0; index < groups.length; index++) {
    const group = object(groups[index]); groupIds.push(uuid(group.groupId))
    const members = array(group.itemIds, 10).map(uuid), queued = array(group.pendingQueue, 10).map(uuid), retries = array(group.retryQueue, 10).map(uuid), passed = array(group.passedLearningItemIds, 10).map(uuid)
    if (!members.length || ![0, 1].includes(group.currentTypeIndex as number)) fail()
    const partition = [...queued, ...retries, ...passed]
    unique(partition, id => id)
    if (partition.length !== members.length || partition.some(id => !members.includes(id))) fail()
    if (group.currentTypeIndex === 1 && (queued.length || retries.length)) fail()
    if (group.currentTypeIndex === 0) { if (!queued.length && !retries.length) fail(); firstActive = Math.min(firstActive, index) }
    for (const id of [...queued, ...retries]) if (!progress[id]) fail()
    for (const id of members) if (progress[id]) {
      const item = object(progress[id]), trials = results(item.results), rating = aggregate(['LISTEN_RECALL'], trials)
      if (queued.includes(id) && trials.length || retries.includes(id) && (rating !== null || !trials.length)
        || passed.includes(id) && rating === null) fail()
      if ((rating !== null) !== !!item.completedAt) fail()
      if (item.completedAt) {
        time(item.completedAt)
        const event = pending.get(uuid(eventLinks[id]))
        if (!event || event.learningItemId !== id || event.completedAt !== item.completedAt
          || JSON.stringify(event.results) !== JSON.stringify(trials)) fail()
      } else if (eventLinks[id]) fail()
      const base = object(baselines[id]); uuid(base.progressEpoch)
      if (base.progressVersion !== '0') time(base.progressVersion)
      if (base.lastReviewEventId != null) uuid(base.lastReviewEventId)
      if (item.completedAt) {
        const event = pending.get(String(eventLinks[id]))!
        if (event.progressEpoch !== base.progressEpoch || event.baseVersion !== base.progressVersion
          || (event.baseEventId ?? null) !== (base.lastReviewEventId ?? null)) fail()
      }
    }
    allIds.push(...members)
  }
  unique(allIds, id => id); unique(groupIds, id => id)
  if (!groups.length || batch.currentGroupIndex !== firstActive
    || Object.keys(progress).some(id => !allIds.includes(id)) || Object.keys(baselines).some(id => !progress[id])
    || Object.keys(eventLinks).some(id => !progress[id])) fail()
  const trialIds = Object.values(batch.items).flatMap(item => item.results.flatMap(result => result.trials.map(trial => trial.id)))
  unique(trialIds, id => id); currentTrainingQuestion(structuredClone(batch))
}
/** 只读取明确支持的恢复表；不导入账号、许可、隔离标记、FSRS 投影或任意对象地址。 */
export function parseRecoveryBackup(text: string, scope: AccountScope): RecoveryBackup {
  if (new TextEncoder().encode(text).length > MAX_BACKUP_BYTES) throw new Error('备份文件超过 20 MB，请缩小范围后重试。')
  const root = object(JSON.parse(text)), local = object(root.local), key = accountKey(scope)
  const safeKeys = (value: unknown, depth = 0): void => {
    if (depth > 50) fail()
    if (value && typeof value === 'object') for (const [name, child] of Object.entries(value)) {
      if (['__proto__', 'constructor', 'prototype'].includes(name)) fail()
      safeKeys(child, depth + 1)
    }
  }
  safeKeys(root)
  const matches = (value: unknown) => { const owner = object(value); return owner.serverId === scope.serverId && owner.userId === scope.userId }
  if (root.application !== 'language-lean' || root.schemaVersion !== 1 || local.schemaVersion !== 1 || local.scope !== 'LOCAL') fail()
  if (!matches(root.account) || !matches(local.account)) throw new Error('只能恢复同一服务器、同一账号 UUID 的备份，同名新账号不能关联旧数据。')
  if (root.server != null && object(root.server).userId !== scope.userId) fail()
  const tables = object(local.tables)
  const scoped = (raw: unknown) => { const row = object(raw); if (row.accountKey !== key) fail(); return row }
  const pending = array(tables.pending, 5000).map(raw => parseReview(scoped(raw)))
  unique(pending, event => event.eventId); unique(pending, event => event.attemptId)
  const events = new Map(pending.map(event => [event.eventId, event]))
  const drafts = array(tables.drafts, 1000).map(raw => {
    const value = scoped(raw)
    if (value.schemaVersion !== 1) fail()
    const row = { accountKey: key, batchId: uuid(value.batchId), schemaVersion: 1, payload: object(value.payload) as unknown as TrainingBatch,
      baselines: value.baselines, events: value.events ?? {} } as DraftRow
    validateDraft(row, events); return row
  })
  unique(drafts, row => row.batchId)
  const resources = array(tables.resources, 1000).map(raw => {
    const value = scoped(raw)
    return { accountKey: key, batchId: uuid(value.batchId), wordbookId: uuid(value.wordbookId),
      createdAt: time(value.createdAt), items: object(value.items) } as TrainingResourceRow
  })
  unique(resources, row => row.batchId)
  for (const draft of drafts) {
    const resource = resources.find(row => row.batchId === draft.batchId)
    if (!resource || Object.keys(draft.payload.items).some(id => !resource.items[id])) fail()
  }
  return { pending, drafts, resources }
}
