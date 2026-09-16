import Dexie, { type Table } from 'dexie'
import type { AccountScope, CompletedReview } from '../../core/reviews'
import type { PendingReviewStore } from '../../core/ports'

/** IndexedDB 中待上传事件的内部结构。 */
interface PendingRow extends CompletedReview { accountKey: string }
/** 中途退出时保存的复习批次草稿。 */
interface DraftRow { accountKey: string; batchId: string; schemaVersion: number; payload: unknown }
/** 指定账号下某个学习条目的本地进度快照。 */
interface StateRow { accountKey: string; learningItemId: string; progressEpoch: string; payload: unknown }
/** 生成稳定的账号分区键，使同一设备可安全缓存多个账号。 */
export function accountKey(scope: AccountScope): string {
  if (!scope.serverId || !scope.userId) throw new Error('Account scope required')
  return JSON.stringify([scope.serverId, scope.userId])
}
/** 浏览器端学习数据库，数据表都按账号分区。 */
export class LearningDatabase extends Dexie {
  pending!: Table<PendingRow, [string, string]>
  drafts!: Table<DraftRow, [string, string]>
  states!: Table<StateRow, [string, string]>
  constructor(name = 'language-lean') {
    super(name)
    this.version(1).stores({
      pending: '[accountKey+eventId], accountKey',
      drafts: '[accountKey+batchId], accountKey',
      states: '[accountKey+learningItemId], accountKey',
    })
  }
}
/** 基于 Dexie 的离线待上传事件仓储。 */
export class DexiePendingReviewStore implements PendingReviewStore {
  constructor(private readonly db: LearningDatabase) {}
  /** 幂等写入已完成事件；相同 eventId 不会产生重复记录。 */
  async enqueue(scope: AccountScope, review: CompletedReview): Promise<void> {
    const key = accountKey(scope)
    await this.db.transaction('rw', this.db.pending, async () => {
      if (!await this.db.pending.get([key, review.eventId]))
        await this.db.pending.add({ ...review, accountKey: key })
    })
  }
  /** 只列出当前账号尚未被服务端确认的事件。 */
  async list(scope: AccountScope): Promise<CompletedReview[]> {
    return this.db.pending.where('accountKey').equals(accountKey(scope)).toArray()
  }
  /** 删除服务端已逐条确认的事件，未确认事件继续留在本地等待重试。 */
  async acknowledge(scope: AccountScope, eventIds: string[]): Promise<void> {
    await this.db.pending.bulkDelete(eventIds.map(id => [accountKey(scope), id]))
  }
}
