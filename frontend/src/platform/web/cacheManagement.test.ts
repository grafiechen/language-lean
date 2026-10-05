import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import fixtures from '../../core/fsrs.fixtures.json'
import { accountKey } from './database'
import { learningDatabase, pendingReviews, reviewSessions } from './reviewSync'
import { startTraining } from './trainingSessions'
import { prepareCachedTraining } from './offlineLearning'
import { availableCachedAccounts } from './cachedAccounts'
import { accountCacheUsage, clearUnusedAudio, clearAccountDownloads, removeOfflineDownload, siteStorageUsage,
  requestStoragePersistence, learningStorageError } from './cacheManagement'

const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
/** 同一版本在两个账号中独立缓存，用于检测误按音频 ID 删除整个站点的错误。 */
function word(audioVersionId = 'shared'): TrainingWord {
  return { learning: { id: fixtures[0].itemId, dictionaryEntryId: 'entry', written: '猫', languageCode: 'ja', status: 'PUBLISHED', currentRevision: 1,
    manualEarFocus: false, progressEpoch: 'epoch', fsrsAlgorithmVersion: 'UNINITIALIZED', fsrsState: '{}', scheduler: fixtures[0].profile,
    reviewCount: 0, lapseCount: 0, lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false },
    entry: { id: 'entry', languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
      content: { schemaVersion: 1, readings: [{ id: 'reading', reading: 'ねこ', pronunciationText: 'ねこ' }], senses: [], sourceName: '', license: '' } },
    readingId: 'reading', audioVersionId, textHash: 'hash' }
}
async function audio(id: string, size: number, scope = owner) {
  await learningDatabase.audio.put({ accountKey: accountKey(scope), audioVersionId: id, requestKey: id,
    cachedAt: '', textHash: 'hash', blob: new Blob([new Uint8Array(size)]) })
}
async function book(id: string, audioIds: string[], scope = owner) {
  const words = audioIds.map(id => word(id))
  await learningDatabase.preparations.put({ accountKey: accountKey(scope), wordbookId: id,
    book: { id, name: id, description: '', itemCount: words.length, createdAt: '', updatedAt: '' }, preparedAt: '',
    status: 'READY', words, items: words.map(word => word.learning), missingPronunciation: 0, failures: [] })
}
beforeEach(async () => { vi.restoreAllMocks(); await Promise.all(learningDatabase.tables.map(table => table.clear())) })
afterAll(() => learningDatabase.delete())

it('counts the current account and removes unused audio without touching another account or references', async () => {
  await audio('shared', 20); await audio('unused', 40); await audio('unused', 90, other); await book('A', ['shared'])
  const before = await accountCacheUsage(owner)
  expect(before.audioBytes).toBe(60); expect(before.unusedAudioBytes).toBe(40); expect(before.contentBytes).toBeGreaterThan(0)
  expect(await clearUnusedAudio(owner)).toEqual({ removedBooks: 0, removedAudio: 1, freedAudioBytes: 40 })
  expect(await learningDatabase.audio.get([accountKey(owner), 'shared'])).toBeDefined()
  expect(await learningDatabase.audio.get([accountKey(other), 'unused'])).toBeDefined()
})
it('protects alternate reading audio for offline ear training, while missing alternatives block the ear batch', async () => {
  await audio('primary', 20); await audio('alternate', 30); await audio('unused', 40); await book('ear', ['primary'])
  const prepared = (await learningDatabase.preparations.get([accountKey(owner), 'ear']))!
  prepared.words[0].learning.manualEarFocus = true
  prepared.words[0].readingAudio = [{ readingId: 'reading', audioVersionId: 'primary', textHash: 'hash' }, { readingId: 'alternate-reading', audioVersionId: 'alternate', textHash: 'hash' }]
  await learningDatabase.preparations.put(prepared)
  expect((await clearUnusedAudio(owner)).removedAudio).toBe(1)
  expect((await prepareCachedTraining(owner, 'ear', true, true)).words).toHaveLength(1)
  await learningDatabase.audio.delete([accountKey(owner), 'alternate'])
  expect((await prepareCachedTraining(owner, 'ear', true, true)).failures).toHaveLength(1)
  expect((await prepareCachedTraining(owner, 'ear', true, false)).words).toHaveLength(1)
})
it('removes only the selected book download and retains shared audio and untouched learning records', async () => {
  await audio('shared', 20); await audio('exclusive', 40); await book('A', ['shared', 'exclusive']); await book('B', ['shared'])
  const batch = await startTraining(owner, 'B', [word()]), saved = await reviewSessions.rate(owner, batch.batchId, word().learning.id, 'GOOD')
  await learningDatabase.issues.put({ accountKey: accountKey(owner), eventId: saved.event!.eventId, learningItemId: saved.event!.learningItemId,
    progressEpoch: 'epoch', status: 409, code: 'BASELINE_MISSING', message: '需要基准', detectedAt: '' })
  const protectedTables = [learningDatabase.pending, learningDatabase.drafts, learningDatabase.resources, learningDatabase.projections, learningDatabase.issues, learningDatabase.states]
  const before = await Promise.all(protectedTables.map(table => table.toArray()))
  expect((await removeOfflineDownload(owner, 'A')).freedAudioBytes).toBe(40)
  expect(await learningDatabase.preparations.get([accountKey(owner), 'A'])).toBeUndefined()
  expect(await learningDatabase.preparations.get([accountKey(owner), 'B'])).toBeDefined()
  expect(await Promise.all(protectedTables.map(table => table.toArray()))).toEqual(before)
})
it('clears account downloads but protects interrupted training and completed pending resources', async () => {
  await audio('shared', 20); await audio('active', 30); await audio('exclusive', 40); await audio('shared', 50, other)
  await book('A', ['shared', 'active', 'exclusive']); await book('A', ['shared'], other)
  const completed = await startTraining(owner, 'A', [word()]); await reviewSessions.rate(owner, completed.batchId, word().learning.id, 'GOOD')
  const active = await startTraining(owner, 'A', [word('active')]); await reviewSessions.rate(owner, active.batchId, word().learning.id, 'AGAIN')
  const result = await clearAccountDownloads(owner)
  expect(result.removedBooks).toBe(1); expect(result.freedAudioBytes).toBe(40)
  expect(await learningDatabase.audio.where('accountKey').equals(accountKey(owner)).count()).toBe(2)
  expect(await learningDatabase.preparations.where('accountKey').equals(accountKey(other)).count()).toBe(1)
  expect(await pendingReviews.list(owner)).toHaveLength(1)
  expect((await accountCacheUsage(owner)).continuing).toEqual([{ wordbookId: 'A', name: '未完成训练' }])
})
it('rolls back the entire cleanup when deleting an audio object fails', async () => {
  await audio('exclusive', 40); await book('A', ['exclusive'])
  const fail = () => { throw new Error('disk delete failed') }; learningDatabase.audio.hook('deleting', fail)
  try {
    await expect(removeOfflineDownload(owner, 'A')).rejects.toThrow('disk delete failed')
    expect(await learningDatabase.preparations.get([accountKey(owner), 'A'])).toBeDefined()
    expect(await learningDatabase.audio.get([accountKey(owner), 'exclusive'])).toBeDefined()
    expect(await learningDatabase.syncMeta.get(accountKey(owner))).toBeUndefined()
  } finally { learningDatabase.audio.hook('deleting').unsubscribe(fail) }
})
it('rejects prepared content after local cleanup while allowing other-account cleanup and preserving the original queue', async () => {
  await audio('shared', 20); await book('A', ['shared'])
  const prepared = await prepareCachedTraining(owner, 'A', true)
  await clearAccountDownloads(other)
  const batch = await startTraining(owner, 'A', prepared.words)
  expect(batch.items[word().learning.id]).toBeDefined()
  await clearAccountDownloads(owner)
  await expect(startTraining(owner, 'A', prepared.words)).rejects.toThrow('重新准备')
  expect(await learningDatabase.drafts.count()).toBe(1)
  expect(await learningDatabase.audio.get([accountKey(owner), 'shared'])).toBeDefined()
})
it('keeps an offline account selectable when only unsubmitted answers remain', async () => {
  await learningDatabase.accounts.put({ accountKey: accountKey(owner), ...owner, username: 'A', lastUsedAt: '' })
  await pendingReviews.enqueue(owner, { eventId: 'event', attemptId: 'attempt', learningItemId: word().learning.id, progressEpoch: 'epoch',
    baseVersion: '0', completedAt: '2026-10-02T00:00:00Z', submissionVersion: '2026-10-02T00:00:00Z', results: [] })
  await clearAccountDownloads(owner)
  expect((await availableCachedAccounts('server')).map(row => row.userId)).toEqual(['A'])
  expect(await availableCachedAccounts('another-server')).toEqual([])
})
it('surfaces quota failure and leaves the answer, event and projection transaction uncommitted', async () => {
  const batch = await startTraining(owner, 'A', [word()])
  const quota = new DOMException('full', 'QuotaExceededError'), fail = () => { throw quota }
  learningDatabase.drafts.hook('updating', fail)
  try {
    await expect(reviewSessions.rate(owner, batch.batchId, word().learning.id, 'GOOD')).rejects.toThrow('full')
    expect(learningStorageError({ inner: quota }, '失败')).toContain('清理未引用音频')
    expect(await pendingReviews.list(owner)).toHaveLength(0); expect(await learningDatabase.projections.count()).toBe(0)
    expect((await learningDatabase.drafts.get([accountKey(owner), batch.batchId]))?.payload.groups[0].pendingQueue).toEqual([word().learning.id])
  } finally { learningDatabase.drafts.hook('updating').unsubscribe(fail) }
})
it('treats quota estimation and persistence as optional browser capabilities', async () => {
  const unsupported = {} as StorageManager
  expect(await siteStorageUsage(unsupported)).toEqual({ persistenceAvailable: false, usage: undefined, quota: undefined, persisted: undefined })
  await expect(requestStoragePersistence(unsupported)).rejects.toThrow('不支持')
  const supported = { estimate: vi.fn().mockResolvedValue({ usage: 40, quota: 100 }), persisted: vi.fn().mockResolvedValue(false),
    persist: vi.fn().mockResolvedValue(false) } as unknown as StorageManager
  expect((await siteStorageUsage(supported)).quota).toBe(100)
  expect(supported.persist).not.toHaveBeenCalled(); expect(await requestStoragePersistence(supported)).toBe(false)
  vi.mocked(supported.estimate).mockRejectedValueOnce(new Error('not permitted'))
  expect((await siteStorageUsage(supported)).usage).toBeUndefined()
})
