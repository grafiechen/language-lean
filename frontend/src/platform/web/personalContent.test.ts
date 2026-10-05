import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it } from 'vitest'
import { learningDatabase, pendingReviews, reviewSessions } from './reviewSync'
import { accountKey } from './database'
import { cachedLearningContent, invalidatePersonalContentDownloads, purgeDeletedPrivateCache } from './personalContent'
import { learningAudioSource, parsePersonalTags } from '../../features/learning/personalContent'
import { startTraining, restoreTraining } from './trainingSessions'
import { prepareCachedTraining } from './offlineLearning'
import type { TrainingWord } from '../../features/learning/trainingTypes'

const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
/** 用真实个人内容快照测试离线读取、共享分类与学习记录保护。 */
function word(): TrainingWord {
  return { learning: { id: 'item', dictionaryEntryId: 'entry', written: '猫', languageCode: 'ja', status: 'PUBLISHED', currentRevision: 1,
    manualEarFocus: false, progressEpoch: 'epoch', fsrsAlgorithmVersion: 'UNINITIALIZED', reviewCount: 0, lapseCount: 0,
    lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false, personalContentRevision: 1 },
    entry: { id: 'entry', languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
      content: { schemaVersion: 1, readings: [], senses: [{ id: 'item', partOfSpeech: '个人释义', gloss: '私有猫', examples: [] }], sourceName: '', license: '' } },
    readingId: 'reading', audioVersionId: 'audio', textHash: 'hash',
    personal: { revision: 1, meaningOverride: '私有猫', notes: '私有笔记', tags: ['动物'], updatedAt: null } }
}
async function prepare(scope = owner, bookId = 'book') {
  const value = word()
  await learningDatabase.preparations.add({ accountKey: accountKey(scope), wordbookId: bookId,
    book: { id: bookId, name: bookId, description: '', itemCount: 1, createdAt: '', updatedAt: '' }, preparedAt: '2026-10-03T00:00:00Z',
    status: 'READY', words: [value], items: [value.learning], missingPronunciation: 0, failures: [] })
  await learningDatabase.audio.put({ accountKey: accountKey(scope), audioVersionId: 'audio', requestKey: '', cachedAt: '', textHash: 'hash', blob: new Blob(['audio']) })
}
beforeEach(async () => { await Promise.all(learningDatabase.tables.map(table => table.clear())) })
afterAll(() => learningDatabase.delete())

it('isolates private snapshots and supports old snapshots without inventing personal content', async () => {
  await prepare()
  expect((await cachedLearningContent(owner, 'item'))?.personal.notes).toBe('私有笔记')
  expect(await cachedLearningContent(other, 'item')).toBeNull()
  await learningDatabase.preparations.delete([accountKey(owner), 'book'])
  const old = word(); delete old.personal; delete old.learning.personalContentRevision
  await startTraining(owner, 'book', [old])
  expect((await cachedLearningContent(owner, 'item'))?.personal).toEqual({ revision: 0, meaningOverride: null, notes: '', tags: [], updatedAt: null })
})
it('invalidates both shared downloads but keeps pending answers and interrupted batch snapshots', async () => {
  await prepare(); await prepare(owner, 'second'); await prepare(other)
  const batch = await startTraining(owner, 'book', [word()])
  await reviewSessions.rate(owner, batch.batchId, 'item', 'AGAIN')
  const second = await startTraining(owner, 'second', [word()]); await reviewSessions.rate(owner, second.batchId, 'item', 'GOOD')
  const before = JSON.stringify(await learningDatabase.drafts.toArray())
  await invalidatePersonalContentDownloads(owner, 'item', 2)
  expect((await learningDatabase.preparations.where('accountKey').equals(accountKey(owner)).toArray()).map(row => row.status)).toEqual(['PARTIAL', 'PARTIAL'])
  expect((await learningDatabase.preparations.get([accountKey(other), 'book']))?.status).toBe('READY')
  expect(await pendingReviews.list(owner)).toHaveLength(1); expect(JSON.stringify(await learningDatabase.drafts.toArray())).toBe(before)
  expect((await restoreTraining(owner, 'book'))?.words.item.personal?.meaningOverride).toBe('私有猫')
  await expect(prepareCachedTraining(owner, 'book', true)).rejects.toThrow('尚未完整准备')
})
it('rolls back invalidation entirely on disk failure and does not remove study data', async () => {
  await prepare(); const batch = await startTraining(owner, 'book', [word()]); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  const fail = () => { throw new Error('disk full') }; learningDatabase.preparations.hook('updating', fail)
  try {
    await expect(invalidatePersonalContentDownloads(owner, 'item', 2)).rejects.toThrow('disk full')
    expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.status).toBe('READY')
    expect(await learningDatabase.syncMeta.count()).toBe(0); expect(await pendingReviews.list(owner)).toHaveLength(1)
  } finally { learningDatabase.preparations.hook('updating').unsubscribe(fail) }
})
it('hides a banned base entry in offline effective detail after remote status reconciliation', async () => {
  await prepare()
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words[0].learning.status = 'BANNED'; await learningDatabase.preparations.put(row)
  const value = await cachedLearningContent(owner, 'item')
  expect(value?.entry.status).toBe('BANNED'); expect(value?.entry.content).toBeNull()
  expect(value?.personal.notes).toBe('私有笔记')
})
it('keeps offline personal detail for a word with no training pronunciation and expires it on edit', async () => {
  await prepare()
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!, value = word()
  row.words = []; row.status = 'EMPTY'; row.missingPronunciation = 1
  row.contents = [{ learningItemId: 'item', entry: value.entry, personal: value.personal! }]
  await learningDatabase.preparations.put(row)
  expect((await cachedLearningContent(owner, 'item'))?.personal.notes).toBe('私有笔记')
  await invalidatePersonalContentDownloads(owner, 'item', 2)
  expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.status).toBe('PARTIAL')
  expect(await cachedLearningContent(other, 'item')).toBeNull()
})
it('accepts Chinese commas, newlines and deduplicates tags while preserving user order', () => {
  expect(parsePersonalTags(' 动物，旅行\n动物, 日语 ,, ')).toEqual(['动物', '旅行', '日语'])
})
it('shows private effective content offline without treating its private status as a ban', async () => {
  await prepare(); const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words[0].learning.status = 'PRIVATE'; row.words[0].learning.dictionaryEntryId = null; row.words[0].learning.personalCustomEntryId = 'entry'
  row.words[0].entry.status = 'PRIVATE'; row.words[0].entry.originType = 'PRIVATE'; row.items = [row.words[0].learning]
  await learningDatabase.preparations.put(row)
  expect((await cachedLearningContent(owner, 'item'))?.entry.content?.senses[0].gloss).toBe('私有猫')
  expect((await cachedLearningContent(owner, 'item'))?.entry.status).toBe('PRIVATE')
  expect(await cachedLearningContent(other, 'item')).toBeNull()
})
it('private deletion removes cached text and all private audio including examples without touching another account or public audio', async () => {
  await prepare(); await prepare(other)
  const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words[0].learning.status = 'PRIVATE'; row.words[0].learning.dictionaryEntryId = null; row.words[0].learning.personalCustomEntryId = 'entry'
  row.words[0].entry.status = 'PRIVATE'; row.words[0].entry.originType = 'PRIVATE'; row.items = [row.words[0].learning]
  row.contents = [{ learningItemId: 'item', entry: row.words[0].entry, personal: row.words[0].personal! }]
  await learningDatabase.preparations.put(row)
  await learningDatabase.audio.update([accountKey(owner), 'audio'], { requestKey: JSON.stringify(['entry', 'PERSONAL', 'WORD', 'reading']) })
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'example-audio', requestKey: JSON.stringify(['entry', 'PERSONAL', 'EXAMPLE', 'example']), cachedAt: '', textHash: 'hash', blob: new Blob(['private-example']) })
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'public', requestKey: JSON.stringify(['entry', 'PUBLISHED', 'WORD', 'reading']), cachedAt: '', textHash: 'hash', blob: new Blob(['public']) })
  const batch = await startTraining(owner, 'book', row.words); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  await purgeDeletedPrivateCache(owner, ['item'])
  expect(await cachedLearningContent(owner, 'item')).toBeNull(); expect(await pendingReviews.list(owner)).toEqual([])
  expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.contents).toEqual([])
  expect(await learningDatabase.resources.count()).toBe(0)
  expect((await learningDatabase.audio.where('accountKey').equals(accountKey(owner)).toArray()).map(row => row.audioVersionId)).toEqual(['public'])
  expect((await cachedLearningContent(other, 'item'))?.personal.notes).toBe('私有笔记')
  expect(await learningDatabase.audio.get([accountKey(other), 'audio'])).toBeDefined()
})
it('an explicit private deletion clears a stale cached learning identity even when the server no longer returns its id', async () => {
  await prepare(); const row = (await learningDatabase.preparations.get([accountKey(owner), 'book']))!
  row.words[0].learning.personalCustomEntryId = 'entry'; row.words[0].learning.dictionaryEntryId = null; row.words[0].entry.originType = 'PRIVATE'; row.items = [row.words[0].learning]
  await learningDatabase.preparations.put(row)
  const batch = await startTraining(owner, 'book', row.words); await reviewSessions.rate(owner, batch.batchId, 'item', 'AGAIN')
  await purgeDeletedPrivateCache(owner, [], ['entry'])
  expect(await cachedLearningContent(owner, 'item')).toBeNull(); expect(await learningDatabase.drafts.count()).toBe(0)
})

it('selects audio scopes by overridden kind and interprets an empty list as an explicit override', () => {
  const value = word(), content = { learningItemId: 'item', entry: value.entry, personal: value.personal! }
  expect(learningAudioSource(content, 'WORD')).toEqual({ entryId: 'entry', scope: 'PUBLISHED' })
  content.personal.sensesOverride = []
  expect(learningAudioSource(content, 'EXAMPLE')).toEqual({ entryId: 'item', scope: 'OVERRIDE', audioRevision: 0 })
  expect(learningAudioSource(content, 'WORD').scope).toBe('PUBLISHED')
  content.personal.readingsOverride = []
  expect(learningAudioSource(content, 'WORD').scope).toBe('OVERRIDE')
})

it('a failed pronunciation cache revocation rolls back drafts, private audio and snapshots while retaining completed events', async () => {
  await prepare(); const batch = await startTraining(owner, 'book', [word()]); await reviewSessions.rate(owner, batch.batchId, 'item', 'GOOD')
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'override', requestKey: JSON.stringify(['item','OVERRIDE','WORD','reading']), cachedAt: '', textHash: 'hash', blob: new Blob(['private']) })
  const fail = () => { throw new Error('disk failure') }; learningDatabase.audio.hook('deleting', fail)
  try {
    await expect(invalidatePersonalContentDownloads(owner, 'item', 2, 1)).rejects.toThrow('disk failure')
    expect(await learningDatabase.resources.count()).toBe(1); expect(await learningDatabase.drafts.count()).toBe(1)
    expect((await learningDatabase.preparations.get([accountKey(owner), 'book']))?.status).toBe('READY')
    expect(await pendingReviews.list(owner)).toHaveLength(1)
  } finally { learningDatabase.audio.hook('deleting').unsubscribe(fail) }
})

it('last public association deletion purges owner override audio without deleting public or other account clips', async () => {
  await prepare(); await prepare(other)
  await learningDatabase.audio.update([accountKey(owner), 'audio'], { requestKey: JSON.stringify(['item','OVERRIDE','EXAMPLE','example']) })
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'public', requestKey: JSON.stringify(['entry','PUBLISHED','WORD','reading']), cachedAt: '', textHash: 'hash', blob: new Blob(['public']) })
  await purgeDeletedPrivateCache(owner, ['item'])
  expect(await learningDatabase.audio.get([accountKey(owner), 'audio'])).toBeUndefined()
  expect(await learningDatabase.audio.get([accountKey(owner), 'public'])).toBeDefined()
  expect(await learningDatabase.audio.get([accountKey(other), 'audio'])).toBeDefined()
})
