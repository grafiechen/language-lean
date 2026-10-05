import type { AccountScope, CompletedReview } from '../../core/reviews'
import { accountKey, bumpCacheRevision } from './database'
import { learningDatabase } from './reviewSync'
import { reconcileLearning } from './reconciliation'

/** 明确的重置/删除先重新核对；未知冲突保留原事件和可展示的原因。 */
export async function recordSyncFailure(scope: AccountScope, event: CompletedReview, cause: unknown): Promise<void> {
  const error = cause as { status?: number; code?: string; message?: string }
  if (error.code === 'PROGRESS_RESET' || error.code === 'LEARNING_ITEM_MISSING') await reconcileLearning(scope)
  const key = accountKey(scope)
  await learningDatabase.transaction('rw', learningDatabase.pending, learningDatabase.issues, async () => {
    if (!await learningDatabase.pending.get([key, event.eventId])) return
    await learningDatabase.issues.put({ accountKey: key, eventId: event.eventId, learningItemId: event.learningItemId,
      progressEpoch: event.progressEpoch, status: error.status ?? 409, code: error.code ?? 'SYNC_CONFLICT',
      message: error.message || '记录暂不能提交，请重试或检查基准记录。', detectedAt: new Date().toISOString() })
  })
}
/** 只有用户明确丢弃时撤销原记录及依赖链，服务器已确认进度保持不变。 */
export async function discardUnsyncedReview(scope: AccountScope, eventId: string): Promise<number> {
  const key = accountKey(scope)
  return learningDatabase.transaction('rw', [learningDatabase.pending, learningDatabase.issues, learningDatabase.drafts,
    learningDatabase.resources, learningDatabase.syncMeta, learningDatabase.projections], async () => {
    const pending = await learningDatabase.pending.where('accountKey').equals(key).toArray()
    const discarded = new Set([eventId])
    let count = -1
    while (count !== discarded.size) {
      count = discarded.size
      pending.forEach(event => { if (event.baseEventId && discarded.has(event.baseEventId)) discarded.add(event.eventId) })
    }
    await bumpCacheRevision(learningDatabase, scope)
    for (const id of discarded) {
      await learningDatabase.pending.delete([key, id]); await learningDatabase.issues.delete([key, id])
      await learningDatabase.projections.delete([key, id])
    }
    for (const draft of await learningDatabase.drafts.where('accountKey').equals(key).toArray()) {
      if (Object.values(draft.events ?? {}).some(id => discarded.has(id))
        || Object.values(draft.baselines ?? {}).some(base => base.lastReviewEventId && discarded.has(base.lastReviewEventId))) {
        await learningDatabase.drafts.delete([key, draft.batchId]); await learningDatabase.resources.delete([key, draft.batchId])
      }
    }
    return pending.filter(event => discarded.has(event.eventId)).length
  })
}
