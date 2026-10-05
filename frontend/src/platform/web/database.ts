import Dexie, { type Table } from 'dexie'
import type { AccountScope, CompletedReview } from '../../core/reviews'
import type { PendingReviewStore, TrainingDraftStore } from '../../core/ports'
import { currentTrainingQuestion, isTrainingItemComplete, recordTrainingRating, type TrainingBatch } from '../../core/training'
import type { Rating } from '../../core/reviews'
import type { ReviewReceipt } from '../../features/learning/types'
import { scheduleOffline, type FsrsProfile } from '../../core/fsrs'
import { aggregate } from '../../core/reviews'

/** IndexedDB 中待上传事件的内部结构。 */
interface PendingRow extends CompletedReview { accountKey: string }
/** 中途退出时保存的复习批次草稿。 */
export interface DraftRow {
  accountKey: string; batchId: string; schemaVersion: number; payload: TrainingBatch
  baselines?: Record<string, LearningBaseline>
  events?: Record<string, string>
}
/** 批次开始时固定的学习归属和进度基准；答题期间不能偷偷换成服务器最新进度。 */
export interface LearningBaseline {
  progressEpoch: string; progressVersion: string; lastReviewEventId: string | null
  fsrsState?: string; scheduler?: FsrsProfile
}
/** 指定账号下某个学习条目的本地进度快照。 */
interface StateRow { accountKey: string; learningItemId: string; progressEpoch: string; payload: unknown }
/** 生成稳定的账号分区键，使同一设备可安全缓存多个账号。 */
export function accountKey(scope: AccountScope): string {
  if (!scope.serverId || !scope.userId) throw new Error('Account scope required')
  return JSON.stringify([scope.serverId, scope.userId])
}
/** 服务器已确认注销后，磁盘清理失败也不能重新选择旧身份；仅保存不透明分区键。 */
export function confirmedAccountClosed(key: string): boolean {
  try { return globalThis.localStorage?.getItem('language-lean.closed-account:' + key) === 'true' }
  catch { return false }
}
/** 浏览器端学习数据库，数据表都按账号分区。 */
export class LearningDatabase extends Dexie {
  pending!: Table<PendingRow, [string, string]>
  drafts!: Table<DraftRow, [string, string]>
  states!: Table<StateRow, [string, string]>
  audio!: Table<AudioCacheRow, [string, string]>
  resources!: Table<TrainingResourceRow, [string, string]>
  accounts!: Table<CachedAccountRow, string>
  preparations!: Table<OfflinePreparationRow, [string, string]>
  issues!: Table<SyncIssueRow, [string, string]>
  syncMeta!: Table<{ accountKey: string; revision: number; retired?: boolean }, string>
  projections!: Table<ReviewProjectionRow, [string, string]>
  constructor(name = 'language-lean') {
    super(name)
    this.version(1).stores({
      pending: '[accountKey+eventId], accountKey',
      drafts: '[accountKey+batchId], accountKey',
      states: '[accountKey+learningItemId], accountKey',
    })
    this.version(2).stores({ audio: '[accountKey+audioVersionId], accountKey', resources: '[accountKey+batchId], accountKey' })
    this.version(3).stores({ accounts: 'accountKey, serverId', preparations: '[accountKey+wordbookId], accountKey' })
    this.version(4).stores({ issues: '[accountKey+eventId], accountKey', syncMeta: 'accountKey' })
    this.version(5).stores({ projections: '[accountKey+eventId], accountKey' })
  }
}
/** 未确认事件的计算结果，与服务器确认状态分表保存，不冒充正式上传。 */
export interface ReviewProjectionRow {
  accountKey: string; eventId: string; learningItemId: string; progressEpoch: string
  completedAt: string; fsrsState: string; nextReviewAt: string; scheduler: FsrsProfile
}
/** 与删除/重置同事务更新，阻止网络中的旧快照回写刚刚变动的本机缓存。 */
export async function bumpCacheRevision(db: LearningDatabase, scope: AccountScope): Promise<void> {
  await requireLiveCache(db, scope)
  const key = accountKey(scope), previous = await db.syncMeta.get(key)
  await db.syncMeta.put({ accountKey: key, revision: (previous?.revision ?? 0) + 1 })
}
/** 已注销 UUID 只保留一个无正文的隔离标记，拒绝迟到的下载、判定和账号恢复回写。 */
export async function requireLiveCache(db: LearningDatabase, scope: AccountScope): Promise<void> {
  const key = accountKey(scope)
  if (confirmedAccountClosed(key) || (await db.syncMeta.get(key))?.retired) throw new Error('账号已注销，不能恢复旧账号数据。')
}
/** 未解决的业务冲突只保存错误元信息；原事件仍在所属账号 pending 中。 */
export interface SyncIssueRow {
  accountKey: string; eventId: string; learningItemId: string; progressEpoch: string
  code: string; message: string; status: number; detectedAt: string
}
/** 离线归属只存稳定身份和显示名，不保存密码、令牌、邮箱或管理员权限。 */
export interface CachedAccountRow { accountKey: string; serverId: string; userId: string; username: string; nativeLanguage?: string; lastUsedAt: string }
/** 单词本离线准备快照与明确的准备状态，失败不能伪装成就绪。 */
export interface OfflinePreparationRow {
  accountKey: string; wordbookId: string; book: import('../../features/learning/types').Wordbook
  preparedAt: string; status: 'PREPARING' | 'READY' | 'PARTIAL' | 'EMPTY'
  words: import('../../features/learning/trainingTypes').TrainingWord[]
  /** 无可训练发音的词也保存私有内容，允许断网整理和查看；旧缓存可缺省。 */
  contents?: import('../../features/learning/personalContent').LearningContent[]
  items: import('../../features/learning/types').LearningItem[]
  missingPronunciation: number; failures: string[]
}
/** 独立音频版本按账号缓存，不能将草稿和其他用户的文件存入公共缓存。 */
export interface AudioCacheRow { accountKey: string; audioVersionId: string; requestKey: string; cachedAt: string; textHash: string; blob: Blob; audioRevision?: number }
/** 一次训练固定的内容快照，恢复时不混入后来修改的词典内容。 */
export interface TrainingResourceRow {
  accountKey: string; batchId: string; wordbookId: string; createdAt: string
  items: Record<string, import('../../features/learning/trainingTypes').TrainingWord>
}
/** 基于 Dexie 的离线待上传事件仓储。 */
export class DexiePendingReviewStore implements PendingReviewStore {
  constructor(private readonly db: LearningDatabase) {}
  /** 幂等写入已完成事件；相同 eventId 不会产生重复记录。 */
  async enqueue(scope: AccountScope, review: CompletedReview): Promise<void> {
    const key = accountKey(scope)
    await this.db.transaction('rw', this.db.pending, this.db.syncMeta, async () => {
      await requireLiveCache(this.db, scope)
      if (!await this.db.pending.get([key, review.eventId]))
        await this.db.pending.add({ ...review, accountKey: key })
    })
  }
  /** 只列出当前账号尚未被服务端确认的事件。 */
  async list(scope: AccountScope): Promise<CompletedReview[]> {
    const rows = await this.db.pending.where('accountKey').equals(accountKey(scope)).toArray()
    return rows.map(({ accountKey: _key, ...event }) => event)
  }
  /** 删除服务端已逐条确认的事件，未确认事件继续留在本地等待重试。 */
  async acknowledge(scope: AccountScope, eventIds: string[]): Promise<void> {
    await this.db.pending.bulkDelete(eventIds.map(id => [accountKey(scope), id]))
  }
}

/** 每次判定原子保存草稿和已完成事件，防止退出或断网时只保存其中一半。 */
export class DexieReviewSessionStore {
  constructor(private readonly db: LearningDatabase) {}

  /** 开始训练时保存基准；同批次已经存在时禁止覆盖中途退出的队列。 */
  async start(scope: AccountScope, batch: TrainingBatch, baselines: Record<string, LearningBaseline>): Promise<void> {
    const key = accountKey(scope)
    for (const itemId of Object.keys(batch.items)) {
      const base = baselines[itemId]
      if (!base?.progressEpoch || !base.progressVersion) throw new Error('Training baseline required')
    }
    await this.db.transaction('rw', this.db.drafts, this.db.syncMeta, async () => {
    await requireLiveCache(this.db, scope)
    await this.db.drafts.add({ accountKey: key, batchId: batch.batchId, schemaVersion: 1,
      payload: JSON.parse(JSON.stringify(batch)) as TrainingBatch,
      baselines: JSON.parse(JSON.stringify(baselines)) as Record<string, LearningBaseline>, events: {} })
    })
  }

  /** 判定成功写入磁盘后才返回新队列；完成某词立即产生一个稳定的待上传事件。 */
  async rate(scope: AccountScope, batchId: string, itemId: string, rating: Rating,
    ratedAt = new Date().toISOString()): Promise<{ batch: TrainingBatch; event?: CompletedReview }> {
    const key = accountKey(scope)
    return this.db.transaction('rw', this.db.drafts, this.db.pending, this.db.projections, this.db.syncMeta, async () => {
      await requireLiveCache(this.db, scope)
      const row = await this.db.drafts.get([key, batchId])
      if (!row || row.schemaVersion !== 1 || !row.baselines?.[itemId]) throw new Error('Training draft or baseline missing')
      recordTrainingRating(row.payload, itemId, rating, ratedAt, crypto.randomUUID())
      let event: CompletedReview | undefined
      if (isTrainingItemComplete(row.payload, itemId)) {
        const progress = row.payload.items[itemId]
        const base = row.baselines[itemId]
        event = { eventId: crypto.randomUUID(), attemptId: crypto.randomUUID(), learningItemId: itemId,
          progressEpoch: base.progressEpoch, baseVersion: base.progressVersion, baseEventId: base.lastReviewEventId,
          submissionVersion: progress.completedAt!, completedAt: progress.completedAt!, results: progress.results }
        await this.db.pending.add({ ...event, accountKey: key })
        // 旧缓存或不支持的配置只保留原始答题，不猜测周期；投影写入失败仍整体回滚。
        let projection: ReviewProjectionRow | undefined
        if (base.fsrsState && base.scheduler) {
          try {
            const finalRating = aggregate(row.payload.orderedReviewTypes.map(type => type.typeId), progress.results)!
            const card = scheduleOffline(itemId, base.fsrsState, finalRating, event.completedAt, base.scheduler)
            projection = { accountKey: key, eventId: event.eventId, learningItemId: itemId, progressEpoch: base.progressEpoch,
              completedAt: event.completedAt, fsrsState: JSON.stringify(card), nextReviewAt: card.due, scheduler: base.scheduler }
          } catch { /* 完成事件仍可联网提交，页面明确提示离线周期不可用。 */ }
        }
        if (projection) await this.db.projections.add(projection)
        row.events ??= {}
        row.events[itemId] = event.eventId
      }
      await this.db.drafts.put(row)
      return { batch: row.payload, event }
    })
  }

  /** 仅在服务端确认同账户、同学习身份和代际后清理词条缓存；未完成词继续保留。 */
  async acknowledge(scope: AccountScope, receipt: ReviewReceipt): Promise<void> {
    const key = accountKey(scope)
    await this.db.transaction('rw', [this.db.pending, this.db.drafts, this.db.states, this.db.resources, this.db.issues, this.db.projections], async () => {
      const event = await this.db.pending.get([key, receipt.eventId])
      if (!event) return
      if (!['APPLIED', 'HISTORICAL', 'DUPLICATE'].includes(receipt.status)
        || event.learningItemId !== receipt.learningItemId || event.progressEpoch !== receipt.progressEpoch)
        throw new Error('Review acknowledgement does not match cached event')
      await this.db.pending.delete([key, receipt.eventId])
      await this.db.issues.delete([key, receipt.eventId])
      await this.db.projections.delete([key, receipt.eventId])
      const previous = await this.db.states.get([key, receipt.learningItemId])
      const old = previous?.payload as ReviewReceipt | undefined
      const newer = !old || old.progressEpoch !== receipt.progressEpoch
        || Date.parse(receipt.progressVersion) > Date.parse(old.progressVersion)
        || Date.parse(receipt.progressVersion) === Date.parse(old.progressVersion)
          && (receipt.lastReviewEventId ?? '') >= (old.lastReviewEventId ?? '')
      if (newer) await this.db.states.put({ accountKey: key, learningItemId: receipt.learningItemId,
        progressEpoch: receipt.progressEpoch, payload: receipt })
      const rows = await this.db.drafts.where('accountKey').equals(key).toArray()
      for (const row of rows) {
        if (row.events?.[receipt.learningItemId] !== receipt.eventId) continue
        delete row.payload.items[receipt.learningItemId]
        delete row.baselines?.[receipt.learningItemId]
        delete row.events[receipt.learningItemId]
        const resource = await this.db.resources.get([key, row.batchId])
        if (resource) {
          delete resource.items[receipt.learningItemId]
          await this.db.resources.put(resource)
        }
        if (!currentTrainingQuestion(row.payload) && !Object.keys(row.events).length) {
          await this.db.drafts.delete([key, row.batchId])
          await this.db.resources.delete([key, row.batchId])
        } else await this.db.drafts.put(row)
      }
    })
  }
}

/** 基于 Dexie 的训练草稿仓储；每次判定后由调用方保存最新批次。 */
export class DexieTrainingDraftStore implements TrainingDraftStore {
  constructor(private readonly db: LearningDatabase) {}

  /** 同一账号同一批次使用覆盖写入，避免退出时留下旧队列。 */
  async save(scope: AccountScope, batch: TrainingBatch): Promise<void> {
    const key = accountKey(scope)
    await this.db.transaction('rw', this.db.drafts, this.db.syncMeta, async () => {
      await requireLiveCache(this.db, scope)
      const previous = await this.db.drafts.get([key, batch.batchId])
      // 通用草稿保存不能抹掉复习会话固定的基准和待确认事件关联。
      await this.db.drafts.put({ ...previous, accountKey: key, batchId: batch.batchId,
        schemaVersion: batch.schemaVersion, payload: batch })
    })
  }

  /** 恢复指定账号的草稿；不存在时由上层创建新批次。 */
  async load(scope: AccountScope, batchId: string): Promise<TrainingBatch | undefined> {
    const row = await this.db.drafts.get([accountKey(scope), batchId])
    return row?.schemaVersion === 1 ? row.payload : undefined
  }

  /** 只有服务端确认对应词条提交后，上层才应删除已完成批次草稿。 */
  async remove(scope: AccountScope, batchId: string): Promise<void> {
    await this.db.drafts.delete([accountKey(scope), batchId])
  }
}
