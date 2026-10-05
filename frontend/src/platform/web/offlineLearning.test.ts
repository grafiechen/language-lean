import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import { learningDatabase, pendingReviews } from './reviewSync'
import { accountKey, type OfflinePreparationRow } from './database'
import { invalidateOfflinePreparations, localLearningItem, prepareCachedTraining, prepareOfflineWordbook, reconcileOfflineMembership, cacheManualEarFocus } from './offlineLearning'
import * as reviewSync from './reviewSync'
import * as trainingSessions from './trainingSessions'
import * as offlineShell from './offlineShell'
import * as api from '../../shared/api'
import { clearAccountDownloads } from './cacheManagement'

const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
/** 离线快照包含真实学习身份和代际，不用写法或用户名匹配进度。 */
function word(): TrainingWord {
  return { learning: { id: 'item', dictionaryEntryId: 'entry', written: '猫', languageCode: 'ja', status: 'PUBLISHED',
    currentRevision: 1, manualEarFocus: false, progressEpoch: 'epoch', fsrsAlgorithmVersion: 'UNINITIALIZED',
    reviewCount: 0, lapseCount: 0, lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false },
    entry: { id: 'entry', languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
      content: { schemaVersion: 1, readings: [{ id: 'reading', reading: 'ねこ', pronunciationText: 'ねこ' }], senses: [], sourceName: '', license: '' } },
    readingId: 'reading', audioVersionId: 'audio', textHash: 'hash' }
}
async function prepared(scope = owner, bookId = 'book') {
  const row: OfflinePreparationRow = { accountKey: accountKey(scope), wordbookId: bookId,
    book: { id: bookId, name: bookId, description: '', itemCount: 1, createdAt: '', updatedAt: '' }, preparedAt: '',
    status: 'READY', words: [word()], items: [word().learning], missingPronunciation: 0, failures: [] }
  await learningDatabase.preparations.put(row)
  await learningDatabase.audio.put({ accountKey: accountKey(scope), audioVersionId: 'audio', requestKey: 'resource', cachedAt: '', textHash: 'hash', blob: new Blob(['audio']) })
  return row
}
beforeEach(async () => { vi.restoreAllMocks(); await Promise.all([learningDatabase.preparations.clear(), learningDatabase.audio.clear(),
  learningDatabase.pending.clear(), learningDatabase.states.clear(), learningDatabase.resources.clear(), learningDatabase.drafts.clear(),
  learningDatabase.issues.clear(), learningDatabase.syncMeta.clear()]) })
afterAll(() => learningDatabase.delete())

it('can start from prepared resources without borrowing audio or books from another account', async () => {
  await prepared()
  expect((await prepareCachedTraining(owner, 'book', false)).words).toHaveLength(1)
  await expect(prepareCachedTraining(other, 'book', false)).rejects.toThrow('尚未完整准备')
  await learningDatabase.audio.delete([accountKey(owner), 'audio']); await prepared(other)
  const missing = await prepareCachedTraining(owner, 'book', false)
  expect(missing.words).toHaveLength(0); expect(missing.failures).toHaveLength(1)
})
it('does not start from interrupted or partial preparations', async () => {
  await prepared()
  await learningDatabase.preparations.update([accountKey(owner), 'book'], { status: 'PARTIAL' })
  await expect(prepareCachedTraining(owner, 'book', true)).rejects.toThrow('尚未完整准备')
})
it('new CSV members invalidate completeness while preserving prepared resources and another account', async () => {
  await prepared(); await prepared(other)
  const newItem = { ...word().learning, id: 'new', dictionaryEntryId: 'new-entry' }
  await reconcileOfflineMembership(owner, 'book', [word().learning, newItem])
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  expect(row.status).toBe('PARTIAL'); expect(row.items).toHaveLength(2); expect(row.words).toHaveLength(1)
  expect(await learningDatabase.audio.get([accountKey(owner), 'audio'])).toBeDefined()
  await expect(prepareCachedTraining(owner, 'book', true)).rejects.toThrow('尚未完整准备')
  expect((await prepareCachedTraining(other, 'book', true)).words).toHaveLength(1)
})
it('manual focus is immediately usable offline across shared books without changing FSRS or another account', async () => {
  await prepared(); await prepared(owner, 'shared'); await prepared(other)
  expect((await prepareCachedTraining(owner, 'book', true, true)).words).toHaveLength(0)
  await cacheManualEarFocus(owner, { ...word().learning, manualEarFocus: true, reviewCount: 99 })
  for (const book of ['book', 'shared']) {
    const focused = await prepareCachedTraining(owner, book, true, true)
    expect(focused.words).toHaveLength(1); expect(focused.words[0].learning.reviewCount).toBe(0)
  }
  expect((await prepareCachedTraining(other, 'book', true, true)).words).toHaveLength(0)
  await cacheManualEarFocus(owner, { ...word().learning, manualEarFocus: false, progressEpoch: 'stale-epoch' })
  expect((await prepareCachedTraining(owner, 'book', true, true)).words).toHaveLength(1)
  await cacheManualEarFocus(owner, { ...word().learning, manualEarFocus: false })
  expect((await prepareCachedTraining(owner, 'book', true, true)).words).toHaveLength(0)
})
it('uses confirmed due dates, retains pending baseline chains and keeps unconfirmed words out of the daily queue', async () => {
  await prepared()
  const future = new Date(Date.now() + 86400000).toISOString(), completed = new Date().toISOString()
  await learningDatabase.states.put({ accountKey: accountKey(owner), learningItemId: 'item', progressEpoch: 'epoch',
    payload: { progressEpoch: 'epoch', progressVersion: completed, lastReviewEventId: 'confirmed', nextReviewAt: future, lastReviewedAt: completed } })
  expect((await prepareCachedTraining(owner, 'book', false)).words).toHaveLength(0)
  const later = new Date(Date.now() + 10).toISOString()
  await pendingReviews.enqueue(owner, { eventId: 'pending', attemptId: 'attempt', learningItemId: 'item', progressEpoch: 'epoch',
    baseVersion: completed, baseEventId: 'confirmed', completedAt: later, submissionVersion: later, results: [] })
  const extra = await prepareCachedTraining(owner, 'book', true)
  expect(extra.words[0].learning.lastReviewEventId).toBe('pending')
  expect(extra.words[0].learning.progressVersion).toBe(later)
  expect((await prepareCachedTraining(owner, 'book', false)).words).toHaveLength(0)
  expect((await localLearningItem(other, word().learning)).lastReviewEventId).toBeNull()
})
it('reset invalidates every prepared book sharing the learning identity but preserves other accounts', async () => {
  await prepared(owner, 'A'); await prepared(owner, 'B'); await prepared(other, 'A')
  await invalidateOfflinePreparations(owner, { item: 'new-epoch' })
  expect((await learningDatabase.preparations.get([accountKey(owner), 'A']))?.status).toBe('PARTIAL')
  expect((await learningDatabase.preparations.get([accountKey(owner), 'B']))?.status).toBe('PARTIAL')
  expect((await learningDatabase.preparations.get([accountKey(other), 'A']))?.status).toBe('READY')
})

it('same-timestamp pending events do not replace a newer confirmed UUID baseline', async () => {
  await prepared()
  const completed = new Date().toISOString(), nextReviewAt = new Date(Date.now() + 86400000).toISOString()
  await learningDatabase.states.put({ accountKey: accountKey(owner), learningItemId: 'item', progressEpoch: 'epoch',
    payload: { progressEpoch: 'epoch', progressVersion: completed, lastReviewEventId: 'z-confirmed', nextReviewAt, lastReviewedAt: completed } })
  await pendingReviews.enqueue(owner, { eventId: 'a-older', attemptId: 'attempt', learningItemId: 'item', progressEpoch: 'epoch',
    baseVersion: '0', completedAt: completed, submissionVersion: completed, results: [] })
  expect((await localLearningItem(owner, word().learning)).lastReviewEventId).toBe('z-confirmed')
})
it('removing a book clears its offline reference but does not erase shared progress in another book', async () => {
  await prepared(owner, 'A'); await prepared(owner, 'B')
  await pendingReviews.enqueue(owner, { eventId: 'event', attemptId: 'attempt', learningItemId: 'item', progressEpoch: 'epoch',
    baseVersion: '0', completedAt: '2026-10-02T00:00:00Z', submissionVersion: '2026-10-02T00:00:00Z', results: [] })
  await reconcileOfflineMembership(owner, 'A', null)
  expect(await learningDatabase.preparations.get([accountKey(owner), 'A'])).toBeUndefined()
  expect((await learningDatabase.preparations.get([accountKey(owner), 'B']))?.status).toBe('READY')
  expect(await pendingReviews.list(owner)).toHaveLength(1)
})

it('persists a Vue membership snapshot after removal without retaining proxies or mutating another book', async () => {
  await prepared(owner, 'A'); await prepared(owner, 'B')
  const members = reactive([word().learning])
  await reconcileOfflineMembership(owner, 'A', members)
  members[0]!.written = '页面随后修改'; members.splice(0)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'A']))?.items[0]?.written).toBe('猫')
  await reconcileOfflineMembership(owner, 'A', members)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'A']))?.items).toEqual([])
  expect((await learningDatabase.preparations.get([accountKey(owner), 'B']))?.items).toHaveLength(1)
})

it.each(['reset', 'delete', 'clear'] as const)('a late download cannot restore preparation after %s', async action => {
  const row = await prepared()
  vi.spyOn(reviewSync, 'currentReviewScope').mockResolvedValue(owner)
  vi.spyOn(offlineShell, 'requireOfflineShell').mockResolvedValue(undefined)
  vi.spyOn(api, 'getJson').mockResolvedValue([word().learning])
  let finish!: (result: trainingSessions.PreparedTraining) => void
  vi.spyOn(trainingSessions, 'prepareTraining').mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const download = prepareOfflineWordbook(owner, row.book, () => {})
  const rejected = expect(download).rejects.toThrow('学习状态已变化')
  await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
  if (action === 'reset') await invalidateOfflinePreparations(owner, { item: 'new-epoch' })
  else if (action === 'clear') await clearAccountDownloads(owner)
  else await reconcileOfflineMembership(owner, row.book.id, null)
  finish({ words: [word()], missingPronunciation: 0, failures: [] })
  await rejected
  const cached = await learningDatabase.preparations.get([accountKey(owner), row.book.id])
  if (action === 'reset') expect(cached?.status).toBe('PARTIAL')
  else expect(cached).toBeUndefined()
})

it('completes a fresh download when its cached learning generation stays current', async () => {
  const row = await prepared()
  vi.spyOn(reviewSync, 'currentReviewScope').mockResolvedValue(owner)
  vi.spyOn(offlineShell, 'requireOfflineShell').mockResolvedValue(undefined)
  vi.spyOn(api, 'getJson').mockResolvedValue([word().learning])
  vi.spyOn(trainingSessions, 'prepareTraining').mockResolvedValue({ words: [word()], missingPronunciation: 0, failures: [] })
  expect((await prepareOfflineWordbook(owner, row.book, () => {})).status).toBe('READY')
  expect((await prepareCachedTraining(owner, row.book.id, true)).words).toHaveLength(1)
})
