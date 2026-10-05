import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { getJson } from '../../shared/api'
import { ensureAudio, cacheReadyAudio } from './audio'
import { prepareTraining, refreshTrainingAudio, startTraining, restoreTraining, invalidateLearningProgress } from './trainingSessions'
import { learningDatabase, pendingReviews, reviewSessions } from './reviewSync'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import { accountKey } from './database'

vi.mock('../../shared/api', () => ({ getJson: vi.fn(), postJson: vi.fn() }))
vi.mock('./audio', () => ({ ensureAudio: vi.fn(), cacheReadyAudio: vi.fn() }))
const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
/** 最小真实数据结构，用于验证固定资源与账号归属。 */
function word(id = 'item'): TrainingWord {
  return { learning: { id, dictionaryEntryId: 'entry-' + id, written: '猫', languageCode: 'ja', status: 'PUBLISHED',
    currentRevision: 1, manualEarFocus: false, progressEpoch: 'old-epoch', fsrsAlgorithmVersion: 'UNINITIALIZED',
    reviewCount: 0, lapseCount: 0, lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false },
    entry: { id: 'entry-' + id, languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
      content: { schemaVersion: 1, readings: [{ id: 'reading-' + id, reading: 'ねこ', pronunciationText: 'ねこ' }], senses: [], sourceName: 'test', license: '' } },
    readingId: 'reading-' + id, audioVersionId: 'audio-' + id, textHash: 'hash' }
}
beforeEach(async () => {
  vi.clearAllMocks()
  await Promise.all([learningDatabase.drafts.clear(), learningDatabase.resources.clear(), learningDatabase.pending.clear(), learningDatabase.states.clear()])
})
afterAll(() => learningDatabase.delete())

it('prepares only focused words and all their pronunciations for an ear batch, retaining mode on restore', async () => {
  const focused = word('focused'), ordinary = word('ordinary'); focused.learning.manualEarFocus = true
  focused.entry.content!.readings.push({ id: 'alternative', reading: 'びょう', pronunciationText: 'びょう' })
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/languages') return [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }] }] as never
    if (path.endsWith('/items')) return [focused.learning, ordinary.learning] as never
    return { learningItemId: focused.learning.id, entry: focused.entry, personal: { revision: 0 } } as never
  })
  vi.mocked(ensureAudio).mockImplementation(async (_scope, request) => ({ status: 'READY', audioVersionId: request.resourceId, textHash: request.resourceId, url: '/audio', stale: false, message: '' }))
  vi.mocked(cacheReadyAudio).mockResolvedValue(new Blob(['audio']))
  const result = await prepareTraining(owner, 'book', true, () => {}, { earFocus: true, allReadings: true })
  expect(result.words).toHaveLength(1); expect(result.words[0].readingAudio).toHaveLength(2)
  expect(ensureAudio).toHaveBeenCalledTimes(2)
  const batch = await startTraining(owner, 'book', result.words, 'EAR')
  expect((await restoreTraining(owner, 'book'))?.batch.trainingMode).toBe('EAR')
  vi.mocked(ensureAudio).mockResolvedValue({ status: 'READY', audioVersionId: 'repaired', textHash: 'new-hash', url: '/audio', stale: false, message: '' })
  const repaired = await refreshTrainingAudio(owner, batch.batchId, focused.learning.id, 'alternative')
  expect(repaired.audioVersionId).not.toBe('repaired'); expect(repaired.readingAudio![1].audioVersionId).toBe('repaired')
})

it('atomically stores reactive Vue resources and restores only the owning account', async () => {
  const batch = await startTraining(owner, 'book', reactive([word()]))
  expect((await restoreTraining(owner, 'book'))?.batch.batchId).toBe(batch.batchId)
  expect((await restoreTraining(owner, 'book'))?.words.item.entry.written).toBe('猫')
  expect(await restoreTraining(other, 'book')).toBeNull()
  const rated = await reviewSessions.rate(owner, batch.batchId, 'item', 'AGAIN')
  expect(rated.event).toBeUndefined()
  expect((await restoreTraining(owner, 'book'))?.batch.groups[0].retryQueue).toEqual(['item'])
})

it('does not create a partial draft if storing the resources fails', async () => {
  const fail = () => { throw new Error('resource write failed') }
  learningDatabase.resources.hook('creating', fail)
  try {
    await expect(startTraining(owner, 'book', [word()])).rejects.toThrow('resource write failed')
    expect(await learningDatabase.drafts.count()).toBe(0)
  } finally { learningDatabase.resources.hook('creating').unsubscribe(fail) }
})

it('audio repair preserves Again trials and the queue through failure and success', async () => {
  const batch = await startTraining(owner, 'book', [word()])
  await reviewSessions.rate(owner, batch.batchId, 'item', 'AGAIN')
  const before = (await restoreTraining(owner, 'book'))!.batch
  vi.mocked(ensureAudio).mockRejectedValueOnce(new Error('network unavailable'))
  await expect(refreshTrainingAudio(owner, batch.batchId, 'item')).rejects.toThrow('network unavailable')
  expect((await restoreTraining(owner, 'book'))!.batch).toEqual(before)
  vi.mocked(ensureAudio).mockResolvedValue({ status: 'READY', audioVersionId: 'repaired', url: '/audio',
    stale: false, textHash: 'hash', message: '' })
  vi.mocked(cacheReadyAudio).mockResolvedValue(new Blob(['audio']))
  await refreshTrainingAudio(owner, batch.batchId, 'item')
  const restored = (await restoreTraining(owner, 'book'))!
  expect(restored.batch).toEqual(before)
  expect(restored.words.item.audioVersionId).toBe('repaired')
  expect(await pendingReviews.list(owner)).toHaveLength(0)
})

it('acknowledgement removes only confirmed word snapshots and removes the completed batch', async () => {
  const batch = await startTraining(owner, 'book', [word('one'), word('two')])
  for (const id of ['one', 'two']) {
    const saved = await reviewSessions.rate(owner, batch.batchId, id, 'GOOD')
    const event = saved.event!
    await reviewSessions.acknowledge(owner, { status: 'APPLIED', eventId: event.eventId,
      learningItemId: id, progressEpoch: event.progressEpoch, progressVersion: event.completedAt,
      lastReviewEventId: event.eventId, reviewCount: 1, lapseCount: 0, lastReviewedAt: event.completedAt,
      nextReviewAt: event.completedAt, automaticEarFocus: false, fsrsAlgorithmVersion: 'test', fsrsState: '{}' })
    const restored = await restoreTraining(owner, 'book')
    if (id === 'one') expect(Object.keys(restored!.words)).toEqual(['two'])
    else expect(restored).toBeNull()
  }
  expect(await learningDatabase.resources.count()).toBe(0)
  expect(await pendingReviews.list(owner)).toHaveLength(0)
})

it('reset removes only obsolete epochs in the affected account', async () => {
  const a = await startTraining(owner, 'book', [word()])
  const b = await startTraining(other, 'book', [word()])
  await reviewSessions.rate(owner, a.batchId, 'item', 'GOOD')
  await reviewSessions.rate(other, b.batchId, 'item', 'GOOD')
  const fresh = { eventId: 'fresh', attemptId: 'fresh', learningItemId: 'item', progressEpoch: 'new-epoch',
    baseVersion: '0', submissionVersion: '2026-10-02T01:00:00Z', completedAt: '2026-10-02T01:00:00Z', results: [] }
  await pendingReviews.enqueue(owner, fresh)
  await invalidateLearningProgress(owner, { item: 'new-epoch' })
  expect((await pendingReviews.list(owner)).map(event => event.eventId)).toEqual(['fresh'])
  expect(await restoreTraining(owner, 'book')).toBeNull()
  expect(await pendingReviews.list(other)).toHaveLength(1)
  expect(await restoreTraining(other, 'book')).toBeNull()
  expect(await learningDatabase.drafts.get([accountKey(other), b.batchId])).toBeDefined()
})

it('distinguishes missing pronunciation from failed audio and never counts either as reviewed', async () => {
  const valid = word('valid'), missing = word('missing')
  missing.entry.content!.readings[0].pronunciationText = ''
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/languages') return [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }] }] as never
    if (path.endsWith('/review-queue')) return [valid.learning, missing.learning] as never
    const value = path.includes('/valid/') ? valid : missing
    return { learningItemId: value.learning.id, entry: value.entry, personal: { revision: 0, meaningOverride: null, notes: '', tags: [], updatedAt: null } } as never
  })
  vi.mocked(ensureAudio).mockResolvedValue({ status: 'FAILED', audioVersionId: null, textHash: null, url: null, stale: false, message: 'generation failed' })
  vi.mocked(cacheReadyAudio).mockRejectedValue(new Error('generation failed'))
  const result = await prepareTraining(owner, 'book', false, () => {})
  expect(result.missingPronunciation).toBe(1)
  expect(result.contents).toHaveLength(2)
  expect(result.failures).toHaveLength(1)
  expect(result.words).toHaveLength(0)
  expect(ensureAudio).toHaveBeenCalledTimes(1)
  expect(await pendingReviews.list(owner)).toHaveLength(0)
})

it('rejects incompatible language protocol before generating audio', async () => {
  vi.mocked(getJson).mockImplementation(async path => path === '/api/v1/languages'
    ? [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 2 }] }] as never : [word().learning] as never)
  const result = await prepareTraining(owner, 'book', true, () => {})
  expect(result.failures[0]).toContain('版本不兼容')
  expect(ensureAudio).not.toHaveBeenCalled()
})

it('prepares the personal effective answer without sending private text to public TTS', async () => {
  const value = word()
  value.entry.content!.senses = [{ id: 'private-sense', partOfSpeech: '个人释义', gloss: '私人的猫', examples: [] }]
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/languages') return [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }] }] as never
    if (path.endsWith('/items')) return [value.learning] as never
    return { learningItemId: 'item', entry: value.entry, personal: { revision: 3, meaningOverride: '私人的猫', notes: 'private-note', tags: ['动物'], updatedAt: null } } as never
  })
  vi.mocked(ensureAudio).mockResolvedValue({ status: 'READY', audioVersionId: 'audio', textHash: 'hash', url: '/audio', stale: false, message: '' })
  vi.mocked(cacheReadyAudio).mockResolvedValue(new Blob(['audio']))
  const result = await prepareTraining(owner, 'book', true, () => {})
  expect(result.failures).toEqual([]); expect(result.words).toHaveLength(1)
  expect(result.words[0].entry.content!.senses[0].gloss).toBe('私人的猫')
  expect(result.words[0].personal?.notes).toBe('private-note'); expect(result.words[0].learning.personalContentRevision).toBe(3)
  expect(ensureAudio).toHaveBeenCalledWith(owner, { entryId: 'entry-item', resourceId: 'reading-item', kind: 'WORD', scope: 'PUBLISHED', pronunciationText: 'ねこ' })
  const batch = await startTraining(owner, 'book', result.words)
  expect((await restoreTraining(owner, 'book'))?.words.item.personal?.notes).toBe('private-note')
  expect((await learningDatabase.drafts.get([accountKey(owner), batch.batchId]))?.baselines?.item.progressVersion).toBe('0')
})

it.each(['WRONG_OWNER', 'BANNED'])('rejects %s content before touching TTS or starting training', async failure => {
  const value = word()
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/languages') return [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }] }] as never
    if (path.endsWith('/items')) return [value.learning] as never
    return { learningItemId: failure === 'WRONG_OWNER' ? 'other' : 'item', entry: { ...value.entry, status: failure === 'BANNED' ? 'BANNED' : 'PUBLISHED' }, personal: { revision: 0 } } as never
  })
  const result = await prepareTraining(owner, 'book', true, () => {})
  expect(result.failures).toHaveLength(1); expect(result.words).toEqual([]); expect(ensureAudio).not.toHaveBeenCalled()
})

it('uses private identity and PERSONAL audio for a private word without creating a public reference', async () => {
  const value = word(); value.learning.dictionaryEntryId = null; value.learning.personalCustomEntryId = 'private-entry'
  value.learning.status = 'PRIVATE'; value.entry.id = 'private-entry'; value.entry.status = 'PRIVATE'; value.entry.originType = 'PRIVATE'
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/languages') return [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }] }] as never
    if (path.endsWith('/items')) return [value.learning] as never
    return { learningItemId: 'item', entry: value.entry, personal: { revision: 0, meaningOverride: null, notes: '', tags: [], updatedAt: null } } as never
  })
  vi.mocked(ensureAudio).mockResolvedValue({ status: 'READY', audioVersionId: 'audio', textHash: 'hash', url: '/audio', stale: false, message: '' })
  vi.mocked(cacheReadyAudio).mockResolvedValue(new Blob(['audio']))
  const result = await prepareTraining(owner, 'book', true, () => {})
  expect(result.failures).toEqual([]); expect(result.words).toHaveLength(1)
  expect(ensureAudio).toHaveBeenCalledWith(owner, { entryId: 'private-entry', resourceId: 'reading-item', kind: 'WORD', scope: 'PERSONAL', pronunciationText: 'ねこ' })
  expect(result.words[0].learning.dictionaryEntryId).toBeNull()
})

it('prepares and repairs owner override audio using the learning identity while retaining the public dictionary identity', async () => {
  const value = word(); value.entry.content!.readings[0].pronunciationText = 'わたし'
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/languages') return [{ code: 'ja', reviewTypes: [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }] }] as never
    if (path.endsWith('/items')) return [value.learning] as never
    return { learningItemId: 'item', entry: value.entry, personal: { revision: 1, meaningOverride: null, notes: '', tags: [], updatedAt: null,
      readingsOverride: value.entry.content!.readings, sensesOverride: null, audioRevision: 1 } } as never
  })
  vi.mocked(ensureAudio).mockResolvedValue({ status: 'READY', audioVersionId: 'audio', textHash: 'hash', url: '/audio', stale: false, message: '' })
  vi.mocked(cacheReadyAudio).mockResolvedValue(new Blob(['audio']))
  const result = await prepareTraining(owner, 'book', true, () => {})
  expect(result.failures).toEqual([]); expect(result.words[0].learning.dictionaryEntryId).toBe('entry-item')
  expect(result.words[0].learning.personalAudioRevision).toBe(1)
  const batch = await startTraining(owner, 'book', result.words); await refreshTrainingAudio(owner, batch.batchId, 'item')
  expect(ensureAudio).toHaveBeenLastCalledWith(owner, { entryId: 'item', resourceId: 'reading-item', kind: 'WORD', scope: 'OVERRIDE', audioRevision: 1, pronunciationText: 'わたし' })
})
