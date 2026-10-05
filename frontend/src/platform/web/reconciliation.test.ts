import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { getJson, postJson } from '../../shared/api'
import { accountKey } from './database'
import { learningDatabase, pendingReviews, reviewSessions } from './reviewSync'
import { reconcileLearning } from './reconciliation'
import { startTraining } from './trainingSessions'
import { invalidateOfflinePreparations } from './offlineLearning'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import type { LearningItem, Wordbook } from '../../features/learning/types'

vi.mock('../../shared/api', () => ({ getJson: vi.fn(), postJson: vi.fn() }))
vi.stubGlobal('window', { location: { origin: 'server' } })
const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
const serverItems = new Map<string, LearningItem>(), serverBooks = new Map<string, { book: Wordbook; learningItemIds: string[] }>()
/** 使用完整缓存格式验证真实 Dexie 事务，不用布尔标记替代草稿和事件。 */
function word(): TrainingWord {
  return { learning: { id: 'item', dictionaryEntryId: 'entry', written: '猫', languageCode: 'ja', status: 'PUBLISHED', currentRevision: 1,
    manualEarFocus: false, progressEpoch: 'epoch', fsrsAlgorithmVersion: 'UNINITIALIZED', reviewCount: 0, lapseCount: 0,
    lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false },
    entry: { id: 'entry', languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
      content: { schemaVersion: 1, readings: [{ id: 'reading', reading: 'ねこ', pronunciationText: 'ねこ' }], senses: [], sourceName: '', license: '' } },
    readingId: 'reading', audioVersionId: 'audio', textHash: 'hash' }
}
async function prepare(scope = owner, id = 'book') {
  const book = { id, name: id, description: '', itemCount: 1, createdAt: '', updatedAt: '' }
  await learningDatabase.preparations.add({ accountKey: accountKey(scope), wordbookId: id, book, preparedAt: '', status: 'READY',
    words: [word()], items: [word().learning], missingPronunciation: 0, failures: [] })
  serverBooks.set(id, { book, learningItemIds: ['item'] }); serverItems.set('item', word().learning)
}
/** 模拟受账号约束、完整返回每个请求身份的 HTTP 快照。 */
function snapshot(body: { learningItemIds: string[]; wordbookIds: string[] }) {
  return { userId: 'A', items: body.learningItemIds.flatMap(id => serverItems.has(id) ? [serverItems.get(id)!] : []),
    missingItemIds: body.learningItemIds.filter(id => !serverItems.has(id)),
    books: body.wordbookIds.flatMap(id => serverBooks.has(id) ? [serverBooks.get(id)!] : []),
    missingBookIds: body.wordbookIds.filter(id => !serverBooks.has(id)) }
}
beforeEach(async () => {
  vi.clearAllMocks(); serverItems.clear(); serverBooks.clear()
  await Promise.all(learningDatabase.tables.map(table => table.clear()))
  vi.mocked(getJson).mockResolvedValue({ id: 'A' })
  vi.mocked(postJson).mockImplementation(async (_path, body) => snapshot(body as Parameters<typeof snapshot>[0]) as never)
})
afterAll(() => { vi.unstubAllGlobals(); return learningDatabase.delete() })

it('remote personal edits invalidate future downloads while keeping completed events and active fixed answers', async () => {
  await prepare(owner, 'A'); await prepare(owner, 'B'); await prepare(other, 'A')
  const active = await startTraining(owner, 'A', [word()]); await reviewSessions.rate(owner, active.batchId, 'item', 'AGAIN')
  const completed = await startTraining(owner, 'B', [word()]); await reviewSessions.rate(owner, completed.batchId, 'item', 'GOOD')
  const drafts = JSON.stringify(await learningDatabase.drafts.toArray()), resources = JSON.stringify(await learningDatabase.resources.toArray())
  serverItems.set('item', { ...word().learning, personalContentRevision: 1 })
  const result = await reconcileLearning(owner)
  expect(result.removed).toBe(0); expect(result.invalidated).toBe(2)
  expect(await pendingReviews.list(owner)).toHaveLength(1)
  expect(JSON.stringify(await learningDatabase.drafts.toArray())).toBe(drafts); expect(JSON.stringify(await learningDatabase.resources.toArray())).toBe(resources)
  expect((await learningDatabase.preparations.get([accountKey(other), 'A']))?.status).toBe('READY')
  expect((await learningDatabase.preparations.get([accountKey(owner), 'A']))?.status).toBe('PARTIAL')
})

it('remote pronunciation changes revoke old drafts and private audio but retain completed review events and another account', async () => {
  await prepare(owner, 'A'); await prepare(owner, 'B'); await prepare(other, 'A')
  const active = await startTraining(owner, 'A', [word()]); await reviewSessions.rate(owner, active.batchId, 'item', 'AGAIN')
  const completed = await startTraining(owner, 'B', [word()]); await reviewSessions.rate(owner, completed.batchId, 'item', 'GOOD')
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'private', requestKey: JSON.stringify(['item','OVERRIDE','WORD','reading']), cachedAt: '', textHash: 'hash', blob: new Blob(['private']) })
  serverItems.set('item', { ...word().learning, personalContentRevision: 1, personalAudioRevision: 1 })
  await reconcileLearning(owner)
  expect(await pendingReviews.list(owner)).toHaveLength(1); expect(await learningDatabase.resources.count()).toBe(0)
  expect(await learningDatabase.drafts.count()).toBe(0); expect(await learningDatabase.audio.count()).toBe(0)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'A']))?.words).toEqual([])
  expect((await learningDatabase.preparations.get([accountKey(other), 'A']))?.status).toBe('READY')
})

it('reconciles override clips played in details without any offline preparation and clears remote deletes or changed versions', async () => {
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'private', requestKey: JSON.stringify(['item','OVERRIDE','EXAMPLE','example']), audioRevision: 1, cachedAt: '', textHash: 'hash', blob: new Blob(['private']) })
  serverItems.set('item', { ...word().learning, personalAudioRevision: 1 })
  await reconcileLearning(owner); expect(await learningDatabase.audio.count()).toBe(1)
  serverItems.set('item', { ...word().learning, personalAudioRevision: 2 })
  await reconcileLearning(owner); expect(await learningDatabase.audio.count()).toBe(0)
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'new', requestKey: JSON.stringify(['item','OVERRIDE','WORD','reading']), audioRevision: 2, cachedAt: '', textHash: 'hash', blob: new Blob(['private']) })
  serverItems.delete('item'); await reconcileLearning(owner); expect(await learningDatabase.audio.count()).toBe(0)
})

it('does not repeatedly invalidate a newly downloaded personal snapshot at the same revision', async () => {
  await prepare()
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words[0].personal = { revision: 2, meaningOverride: 'private', notes: '', tags: [], updatedAt: null }
  row.words[0].learning.personalContentRevision = 2; row.items[0].personalContentRevision = 2
  await learningDatabase.preparations.put(row); serverItems.set('item', row.items[0])
  expect((await reconcileLearning(owner)).invalidated).toBe(0)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.status).toBe('READY')
})
it('accepts unchanged private snapshots and expires changed private structure without discarding finished events', async () => {
  await prepare()
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words[0].learning.status = 'PRIVATE'; row.words[0].learning.dictionaryEntryId = null; row.words[0].learning.personalCustomEntryId = 'entry'
  row.words[0].entry.status = 'PRIVATE'; row.words[0].entry.originType = 'PRIVATE'; row.items = [row.words[0].learning]
  await learningDatabase.preparations.put(row); serverItems.set('item', row.items[0])
  const batch = await startTraining(owner, 'book', row.words); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  expect((await reconcileLearning(owner)).invalidated).toBe(0)
  serverItems.set('item', { ...row.items[0], currentRevision: 2 })
  await reconcileLearning(owner)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.status).toBe('PARTIAL')
  expect(await pendingReviews.list(owner)).toHaveLength(1); expect(await learningDatabase.resources.count()).toBe(0)
})

it('remote reset clears obsolete events and batches in the owning account and invalidates both shared books', async () => {
  await prepare(owner, 'A'); await prepare(owner, 'B'); await prepare(other, 'A')
  const batch = await startTraining(owner, 'A', [word()])
  const saved = await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  await pendingReviews.enqueue(other, saved.event!)
  serverItems.set('item', { ...word().learning, progressEpoch: 'new-epoch' })
  const result = await reconcileLearning(owner)
  expect(result.removed).toBe(1); expect(result.invalidated).toBe(3)
  expect(await pendingReviews.list(owner)).toHaveLength(0); expect(await pendingReviews.list(other)).toHaveLength(1)
  expect(await learningDatabase.drafts.count()).toBe(0)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'B']))?.status).toBe('PARTIAL')
  expect((await learningDatabase.preparations.get([accountKey(other), 'A']))?.status).toBe('READY')
})
it('deleting one shared book revokes its cache while retaining the live shared event and other book', async () => {
  await prepare(owner, 'A'); await prepare(owner, 'B')
  const batch = await startTraining(owner, 'A', [word()]); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  serverBooks.delete('A')
  expect((await reconcileLearning(owner)).removed).toBe(0)
  expect(await pendingReviews.list(owner)).toHaveLength(1)
  expect(await learningDatabase.preparations.get([accountKey(owner), 'A'])).toBeUndefined()
  expect((await learningDatabase.preparations.get([accountKey(owner), 'B']))?.status).toBe('READY')
})
it('last-reference deletion removes the old identity without attaching it to a newly added identity', async () => {
  await prepare(); const batch = await startTraining(owner, 'book', [word()]); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  serverItems.delete('item'); serverBooks.get('book')!.learningItemIds = ['new-identity']
  await reconcileLearning(owner)
  expect(await pendingReviews.list(owner)).toHaveLength(0)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.words).toHaveLength(0)
  expect(await learningDatabase.states.get([accountKey(owner), 'item'])).toBeUndefined()
})
it('wrong accounts, incomplete snapshots and network failures never delete local events', async () => {
  await prepare(); const batch = await startTraining(owner, 'book', [word()]); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  vi.mocked(getJson).mockResolvedValue({ id: 'B' })
  await expect(reconcileLearning(owner)).rejects.toThrow('所属账号'); expect(postJson).not.toHaveBeenCalled()
  vi.mocked(getJson).mockResolvedValue({ id: 'A' })
  vi.mocked(postJson).mockResolvedValue({ userId: 'A', items: [], missingItemIds: [], books: [], missingBookIds: [] })
  await expect(reconcileLearning(owner)).rejects.toThrow('不完整')
  vi.mocked(postJson).mockRejectedValue(new TypeError('network down'))
  await expect(reconcileLearning(owner)).rejects.toThrow('network down')
  expect(await pendingReviews.list(owner)).toHaveLength(1); expect(await learningDatabase.drafts.count()).toBe(1)
})
it('rolls back pending-event cleanup if persisting preparation invalidation fails', async () => {
  await prepare(); const batch = await startTraining(owner, 'book', [word()]); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  serverItems.set('item', { ...word().learning, progressEpoch: 'new-epoch' })
  const fail = () => { throw new Error('disk full') }; learningDatabase.preparations.hook('updating', fail)
  try {
    await expect(reconcileLearning(owner)).rejects.toThrow('disk full')
    expect(await pendingReviews.list(owner)).toHaveLength(1)
    expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.status).toBe('READY')
    expect(await learningDatabase.drafts.count()).toBe(1)
  } finally { learningDatabase.preparations.hook('updating').unsubscribe(fail) }
})
it('retries a stale network response after a concurrent local reset instead of restoring its old epoch', async () => {
  await prepare()
  vi.mocked(postJson).mockImplementationOnce(async (_path, body) => {
    const old = snapshot(body as Parameters<typeof snapshot>[0])
    await invalidateOfflinePreparations(owner, { item: 'new-epoch' })
    serverItems.set('item', { ...word().learning, progressEpoch: 'new-epoch' })
    return old as never
  })
  await reconcileLearning(owner)
  expect(postJson).toHaveBeenCalledTimes(2)
  expect((await learningDatabase.states.get([accountKey(owner), 'item']))?.progressEpoch).toBe('new-epoch')
})
it('chunks more than 200 identities and applies only after every response is complete', async () => {
  const rows = Array.from({ length: 205 }, (_value, index) => {
    const item = { ...word().learning, id: 'item-' + index }; serverItems.set(item.id, item)
    return { accountKey: accountKey(owner), learningItemId: item.id, progressEpoch: item.progressEpoch, payload: item }
  })
  await learningDatabase.states.bulkPut(rows); await reconcileLearning(owner)
  expect(postJson).toHaveBeenCalledTimes(2)
  expect((vi.mocked(postJson).mock.calls[0][1] as { learningItemIds: string[] }).learningItemIds).toHaveLength(200)
  expect(await learningDatabase.states.count()).toBe(205)
})
