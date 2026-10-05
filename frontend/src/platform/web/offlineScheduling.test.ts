import 'fake-indexeddb/auto'
import { reactive } from 'vue'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import fixtures from '../../core/fsrs.fixtures.json'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import type { ReviewReceipt } from '../../features/learning/types'
import { accountKey, LearningDatabase } from './database'
import { learningDatabase, pendingReviews, reviewSessions } from './reviewSync'
import { startTraining, invalidateLearningProgress, restoreTraining } from './trainingSessions'
import { localLearningItem, prepareCachedTraining } from './offlineLearning'
import { discardUnsyncedReview } from './syncIssues'

const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
const itemId = fixtures[0].itemId, time = '2026-10-01T00:00:00.000Z'
/** 固定真正服务器字段，原始算法参数直接取独立 Java 基准。 */
function word(): TrainingWord {
  return { learning: { id: itemId, dictionaryEntryId: 'entry', written: '猫', languageCode: 'ja', status: 'PUBLISHED', currentRevision: 1,
    manualEarFocus: false, progressEpoch: 'epoch', fsrsAlgorithmVersion: 'UNINITIALIZED', fsrsState: '{}', scheduler: fixtures[0].profile,
    reviewCount: 0, lapseCount: 0, lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false },
    entry: { id: 'entry', languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
      content: { schemaVersion: 1, readings: [{ id: 'reading', reading: 'ねこ', pronunciationText: 'ねこ' }], senses: [], sourceName: '', license: '' } },
    readingId: 'reading', audioVersionId: 'audio', textHash: 'hash' }
}
async function prepare() {
  const book = { id: 'book', name: 'book', description: '', itemCount: 1, createdAt: '', updatedAt: '' }
  await learningDatabase.preparations.put({ accountKey: accountKey(owner), wordbookId: 'book', book, preparedAt: time,
    status: 'READY', words: [word()], items: [word().learning], missingPronunciation: 0, failures: [] })
  await learningDatabase.audio.put({ accountKey: accountKey(owner), audioVersionId: 'audio', requestKey: 'audio', cachedAt: time, textHash: 'hash', blob: new Blob(['audio']) })
}
beforeEach(async () => { vi.restoreAllMocks(); await Promise.all(learningDatabase.tables.map(table => table.clear())) })
afterAll(() => learningDatabase.delete())

it('persists the worst final rating projection only after the word passes, including reactive profile snapshots', async () => {
  const batch = await startTraining(owner, 'book', reactive([word()]))
  await reviewSessions.rate(owner, batch.batchId, itemId, 'AGAIN', time)
  expect(await learningDatabase.projections.count()).toBe(0)
  const saved = await reviewSessions.rate(owner, batch.batchId, itemId, 'GOOD', '2026-10-01T00:00:01.000Z')
  const forecast = await learningDatabase.projections.get([accountKey(owner), saved.event!.eventId])
  expect(forecast?.nextReviewAt).toBe('2026-10-01T00:01:01.000Z')
  expect(JSON.parse(forecast!.fsrsState).stability).toBe(fixtures[0].stateAfter.stability)
  expect(await pendingReviews.list(owner)).toHaveLength(1)
  expect((await localLearningItem(other, word().learning)).progressVersion).toBe('0')
})
it('rolls back the queue and event when storing its offline projection fails', async () => {
  const batch = await startTraining(owner, 'book', [word()])
  const fail = () => { throw new Error('projection disk full') }; learningDatabase.projections.hook('creating', fail)
  try {
    await expect(reviewSessions.rate(owner, batch.batchId, itemId, 'GOOD', time)).rejects.toThrow('projection disk full')
    expect(await pendingReviews.list(owner)).toHaveLength(0)
    expect((await learningDatabase.drafts.get([accountKey(owner), batch.batchId]))?.payload.groups[0].pendingQueue).toEqual([itemId])
  } finally { learningDatabase.projections.hook('creating').unsubscribe(fail) }
})
it('returns offline words when their projected period is due and preserves consecutive pending baseline chains after reopening storage', async () => {
  await prepare(); vi.spyOn(Date, 'now').mockReturnValue(Date.parse(time))
  const first = await startTraining(owner, 'book', (await prepareCachedTraining(owner, 'book', false)).words)
  const saved = await reviewSessions.rate(owner, first.batchId, itemId, 'GOOD', time)
  expect(await restoreTraining(owner, 'book')).toBeNull()
  expect((await prepareCachedTraining(owner, 'book', false)).words).toHaveLength(0)
  const reopened = new LearningDatabase()
  try { expect((await reopened.projections.get([accountKey(owner), saved.event!.eventId]))?.nextReviewAt).toBe('2026-10-01T00:10:00.000Z') }
  finally { reopened.close() }
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-01T00:11:00.000Z'))
  const due = await prepareCachedTraining(owner, 'book', false)
  expect(due.words).toHaveLength(1); expect(due.words[0].learning.pendingSchedule).toBe('PROJECTED')
  const second = await startTraining(owner, 'book', due.words)
  const next = await reviewSessions.rate(owner, second.batchId, itemId, 'GOOD', '2026-10-01T00:11:00.000Z')
  expect(next.event!.baseEventId).toBe(saved.event!.eventId)
  expect((await prepareCachedTraining(owner, 'book', false)).words).toHaveLength(0)
  const learning = await localLearningItem(owner, word().learning)
  expect(learning.reviewCount).toBe(2); expect(JSON.parse(learning.fsrsState!).state).toBe('REVIEW')
})
it('replaces a confirmed forecast without removing a newer dependent pending forecast or another account', async () => {
  const first = await startTraining(owner, 'book', [word()]), saved = await reviewSessions.rate(owner, first.batchId, itemId, 'GOOD', time)
  const base = await localLearningItem(owner, word().learning)
  const second = await startTraining(owner, 'book', [{ ...word(), learning: base }])
  const next = await reviewSessions.rate(owner, second.batchId, itemId, 'GOOD', '2026-10-01T00:11:00.000Z')
  const projection = (await learningDatabase.projections.get([accountKey(owner), saved.event!.eventId]))!
  await learningDatabase.projections.put({ ...projection, accountKey: accountKey(other) })
  const receipt: ReviewReceipt = { status: 'APPLIED', eventId: saved.event!.eventId, learningItemId: itemId, progressEpoch: 'epoch',
    progressVersion: time, lastReviewEventId: saved.event!.eventId, fsrsAlgorithmVersion: fixtures[0].profile.algorithmVersion,
    fsrsState: projection.fsrsState, lastReviewedAt: time, nextReviewAt: projection.nextReviewAt, reviewCount: 1, lapseCount: 0, automaticEarFocus: false }
  await reviewSessions.acknowledge(owner, receipt)
  expect(await learningDatabase.projections.get([accountKey(owner), saved.event!.eventId])).toBeUndefined()
  expect(await learningDatabase.projections.get([accountKey(other), saved.event!.eventId])).toBeDefined()
  const remaining = await localLearningItem(owner, word().learning)
  expect(remaining.reviewCount).toBe(2); expect(remaining.lastReviewEventId).toBe(next.event!.eventId)
  const final = (await learningDatabase.projections.get([accountKey(owner), next.event!.eventId]))!
  await reviewSessions.acknowledge(owner, { ...receipt, eventId: next.event!.eventId, progressVersion: next.event!.completedAt,
    lastReviewEventId: next.event!.eventId, lastReviewedAt: next.event!.completedAt, fsrsState: final.fsrsState, nextReviewAt: final.nextReviewAt, reviewCount: 2 })
  expect((await localLearningItem(owner, word().learning)).pendingSchedule).toBeUndefined()
})
it('clears invalidated or explicitly discarded projections only within their account', async () => {
  const batch = await startTraining(owner, 'book', [word()]), saved = await reviewSessions.rate(owner, batch.batchId, itemId, 'GOOD', time)
  const projection = (await learningDatabase.projections.get([accountKey(owner), saved.event!.eventId]))!
  await learningDatabase.projections.put({ ...projection, accountKey: accountKey(other) })
  await discardUnsyncedReview(owner, saved.event!.eventId)
  expect(await learningDatabase.projections.get([accountKey(owner), saved.event!.eventId])).toBeUndefined()
  expect(await learningDatabase.projections.get([accountKey(other), saved.event!.eventId])).toBeDefined()
  const nextBatch = await startTraining(owner, 'book', [word()])
  await reviewSessions.rate(owner, nextBatch.batchId, itemId, 'GOOD', time)
  await invalidateLearningProgress(owner, { [itemId]: 'new-epoch' })
  expect(await learningDatabase.projections.where('accountKey').equals(accountKey(owner)).count()).toBe(0)
  expect(await learningDatabase.projections.where('accountKey').equals(accountKey(other)).count()).toBe(1)
})
it('keeps legacy answers without guessing a cycle or reusing the old card against their new event ID', async () => {
  const legacy = word(); delete legacy.learning.scheduler
  const batch = await startTraining(owner, 'book', [legacy])
  await reviewSessions.rate(owner, batch.batchId, itemId, 'GOOD', time)
  const learning = await localLearningItem(owner, legacy.learning)
  expect(await pendingReviews.list(owner)).toHaveLength(1); expect(await learningDatabase.projections.count()).toBe(0)
  expect(learning.pendingSchedule).toBe('UNAVAILABLE'); expect(learning.fsrsState).toBeUndefined(); expect(learning.due).toBe(false)
})

it('keeps due words ahead of new words without limiting the offline daily queue', async () => {
  await prepare(); vi.spyOn(Date, 'now').mockReturnValue(Date.parse(time))
  const fresh = word(), due = word()
  fresh.learning.id = '87654321-4321-4321-9876-123456789abc'
  due.learning.nextReviewAt = '2026-09-30T23:00:00.000Z'
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words = [fresh, due]; row.items = row.words.map(value => value.learning)
  await learningDatabase.preparations.put(row)
  expect((await prepareCachedTraining(owner, 'book', false)).words.map(value => value.learning.id)).toEqual([itemId, fresh.learning.id])
})
