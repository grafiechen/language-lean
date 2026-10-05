import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api'
import { accountKey } from './database'
import { learningDatabase, pendingReviews, reviewSessions } from './reviewSync'
import { createTrainingBatch } from '../../core/training'
import { discardUnsyncedReview, recordSyncFailure } from './syncIssues'
import { reconcileLearning } from './reconciliation'

vi.mock('./reconciliation', () => ({ reconcileLearning: vi.fn() }))
const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
beforeEach(async () => { vi.clearAllMocks(); await Promise.all(learningDatabase.tables.map(table => table.clear())) })
afterAll(() => learningDatabase.delete())
/** 真实原子判定产生原始事件，用于确认冲突提示不改写其内容。 */
async function completed(scope = owner) {
  const batch = createTrainingBatch(['item'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
  await reviewSessions.start(scope, batch, { item: { progressEpoch: 'epoch', progressVersion: '0', lastReviewEventId: null } })
  return (await reviewSessions.rate(scope, batch.batchId, 'item', 'GOOD')).event!
}
it('keeps original events on ordinary conflicts and removes the issue only on matching acknowledgement', async () => {
  const event = await completed()
  await pendingReviews.enqueue(other, event)
  const cause = new ApiError(409, 'BASELINE_MISSING', '请先上传基准记录')
  await recordSyncFailure(owner, event, cause); await recordSyncFailure(other, event, cause)
  expect(await pendingReviews.list(owner)).toEqual([event])
  expect((await learningDatabase.issues.get([accountKey(owner), event.eventId]))?.message).toBe('请先上传基准记录')
  await reviewSessions.acknowledge(owner, { status: 'APPLIED', eventId: event.eventId, learningItemId: 'item', progressEpoch: 'epoch',
    progressVersion: event.completedAt, lastReviewEventId: event.eventId, fsrsAlgorithmVersion: 'test', fsrsState: '{}',
    lastReviewedAt: event.completedAt, nextReviewAt: event.completedAt, reviewCount: 1, lapseCount: 0, automaticEarFocus: false })
  expect(await learningDatabase.issues.get([accountKey(owner), event.eventId])).toBeUndefined()
  expect(await learningDatabase.issues.get([accountKey(other), event.eventId])).toBeDefined()
})
it('confirmed reset cleanup does not leave a stale conflict entry after removing the event', async () => {
  const event = await completed()
  vi.mocked(reconcileLearning).mockImplementationOnce(async () => { await pendingReviews.acknowledge(owner, [event.eventId]); return { removed: 1, invalidated: 1 } })
  await recordSyncFailure(owner, event, new ApiError(409, 'PROGRESS_RESET', '进度已重置'))
  expect(reconcileLearning).toHaveBeenCalledWith(owner); expect(await learningDatabase.issues.count()).toBe(0)
})
it('explicit discard removes the transitive pending chain and related drafts while preserving other accounts and confirmed progress', async () => {
  const root = await completed()
  const child = { ...root, eventId: 'child', attemptId: 'child', baseEventId: root.eventId, baseVersion: root.completedAt }
  const grandchild = { ...child, eventId: 'grandchild', attemptId: 'grandchild', baseEventId: child.eventId }
  const independent = { ...root, eventId: 'independent', attemptId: 'independent' }
  for (const event of [child, grandchild, independent]) await pendingReviews.enqueue(owner, event)
  await pendingReviews.enqueue(other, root)
  const draft = createTrainingBatch(['unfinished'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
  await reviewSessions.start(owner, draft, { unfinished: { progressEpoch: 'epoch', progressVersion: root.completedAt, lastReviewEventId: child.eventId } })
  await learningDatabase.states.put({ accountKey: accountKey(owner), learningItemId: 'item', progressEpoch: 'epoch', payload: { confirmed: true } })
  expect(await discardUnsyncedReview(owner, root.eventId)).toBe(3)
  expect((await pendingReviews.list(owner)).map(event => event.eventId)).toEqual(['independent'])
  expect(await pendingReviews.list(other)).toHaveLength(1)
  expect(await learningDatabase.drafts.count()).toBe(0)
  expect((await learningDatabase.states.get([accountKey(owner), 'item']))?.payload).toEqual({ confirmed: true })
})
