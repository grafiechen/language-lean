import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { learningDatabase as db, reviewSessions, pendingReviews } from './reviewSync'
import { startTraining, restoreTraining } from './trainingSessions'
import { learningExport } from './learningExport'
import { previewLearningRecovery, applyLearningRecovery } from './learningRecovery'
import { parseRecoveryBackup, parseReview } from './learningRecoveryFormat'
import { getJson, postJson } from '../../shared/api'
import { accountKey } from './database'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import type { LearningContent } from '../../features/learning/personalContent'
import type { CompletedReview } from '../../core/reviews'
import fixtures from '../../core/fsrs.fixtures.json'
import { pronunciationHash } from './audio'
vi.mock('../../shared/api', async original => ({ ...await original<typeof import('../../shared/api')>(), getJson: vi.fn(), postJson: vi.fn() }))
const origin = 'https://learn.test', owner = { serverId: origin, userId: crypto.randomUUID() }, other = { ...owner, userId: crypto.randomUUID() }, book = crypto.randomUUID()
const words = new Map<string, TrainingWord>(), accepted = new Map<string, CompletedReview>(), deleted = new Set<string>()
let currentOwner = owner.userId
vi.stubGlobal('window', { location: { origin } })
beforeEach(async () => {
  vi.clearAllMocks(); words.clear(); accepted.clear(); deleted.clear(); currentOwner = owner.userId
  await Promise.all(db.tables.map(table => table.clear()))
  vi.mocked(getJson).mockImplementation(async path => {
    if (path === '/api/v1/auth/me') return { id: currentOwner }
    const id = path.split('/').at(-2)!, word = words.get(id)!
    return { learningItemId: id, entry: word.entry, personal: word.personal } as LearningContent
  })
  vi.mocked(postJson).mockImplementation(async (_path, raw) => {
    const body = raw as { learningItemIds: string[]; wordbookIds: string[]; eventIds: string[] }
    return { state: { userId: currentOwner, items: body.learningItemIds.filter(id => words.has(id) && !deleted.has(id)).map(id => words.get(id)!.learning),
      missingItemIds: body.learningItemIds.filter(id => !words.has(id) || deleted.has(id)),
      books: body.wordbookIds.includes(book) ? [{ book: { id: book, name: '当前本', description: '', itemCount: words.size, createdAt: '', updatedAt: '' }, learningItemIds: [...words.keys()].filter(id => !deleted.has(id)) }] : [],
      missingBookIds: body.wordbookIds.filter(id => id !== book) },
      events: body.eventIds.map(eventId => ({ eventId, status: accepted.has(eventId) ? 'ACCEPTED' : 'MISSING', submission: accepted.get(eventId) ?? null })) }
  })
})
afterAll(async () => { vi.unstubAllGlobals(); await db.delete() })
/** 用实际训练和导出路径生成备份，而不是手工拼接一份碰巧匹配实现的 JSON。 */
async function backup() {
  const values: TrainingWord[] = []
  for (let index = 0; index < 2; index++) {
    const id = crypto.randomUUID(), entryId = crypto.randomUUID(), readingId = crypto.randomUUID()
    const word: TrainingWord = { learning: { id, dictionaryEntryId: entryId, written: '猫', languageCode: 'ja', status: 'PUBLISHED', currentRevision: 1,
      manualEarFocus: false, progressEpoch: crypto.randomUUID(), fsrsAlgorithmVersion: 'UNINITIALIZED', fsrsState: '{}', scheduler: fixtures[0].profile,
      reviewCount: 0, lapseCount: 0, lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null, automaticEarFocus: false },
      entry: { id: entryId, languageCode: 'ja', scriptCode: 'Jpan', written: '猫', status: 'PUBLISHED', originType: 'ADMIN', currentRevision: 1, hasDraft: false,
        content: { schemaVersion: 1, readings: [{ id: readingId, reading: 'ねこ', pronunciationText: 'ねこ' }], senses: [], sourceName: '', license: '' } },
      personal: { revision: 0, meaningOverride: null, notes: '当前服务器笔记', tags: [], updatedAt: null }, readingId,
      audioVersionId: crypto.randomUUID(), textHash: await pronunciationHash('ねこ') }
    values.push(word); words.set(id, word)
  }
  const batch = await startTraining(owner, book, values)
  const saved = await reviewSessions.rate(owner, batch.batchId, values[0].learning.id, 'GOOD', '2026-09-30T01:00:00.000Z')
  await reviewSessions.rate(owner, batch.batchId, values[1].learning.id, 'AGAIN', '2026-09-30T01:00:01.000Z')
  const value = await learningExport(owner, false), text = JSON.stringify(value)
  await Promise.all(db.tables.map(table => table.clear()))
  return { text, value, batch, values, event: saved.event! }
}
it('previews without writes and restores actual pending events and the original Again queue with current server content', async () => {
  const f = await backup(), plan = await previewLearningRecovery(owner, f.text)
  expect(plan).toMatchObject({ pending: 1, drafts: 1, accepted: 0 }); expect(await db.pending.count()).toBe(0)
  expect(await db.drafts.count()).toBe(0)
  await db.pending.put({ accountKey: accountKey(other), ...f.event })
  await applyLearningRecovery(plan)
  expect(await pendingReviews.list(owner)).toEqual([f.event]); expect(await pendingReviews.list(other)).toEqual([f.event])
  const restored = await restoreTraining(owner, book)
  expect(restored!.batch.batchId).toBe(f.batch.batchId)
  expect(restored!.batch.groups[0].retryQueue).toEqual([f.values[1].learning.id])
  expect(restored!.words[f.values[1].learning.id].personal?.notes).toBe('当前服务器笔记')
  expect(await db.audio.count()).toBe(0); expect(await db.projections.count()).toBe(0)
})
it('skips server-accepted events and removes their completed item association without losing unfinished retry work', async () => {
  const f = await backup(); accepted.set(f.event.eventId, { ...f.event, completedAt: '2026-09-30T01:00:00Z' })
  const plan = await previewLearningRecovery(owner, f.text); expect(plan).toMatchObject({ pending: 0, accepted: 1, drafts: 1 })
  await applyLearningRecovery(plan); expect(await pendingReviews.list(owner)).toEqual([])
  const restored = await restoreTraining(owner, book)
  expect(Object.keys(restored!.batch.items)).toEqual([f.values[1].learning.id]); expect(restored!.batch.groups[0].retryQueue).toEqual([f.values[1].learning.id])
})
it('excludes deleted or reset progress and does not recreate a server wordbook or private entry', async () => {
  const f = await backup(); words.get(f.event.learningItemId)!.learning.progressEpoch = crypto.randomUUID(); deleted.add(f.values[1].learning.id)
  const plan = await previewLearningRecovery(owner, f.text); expect(plan).toMatchObject({ pending: 0, drafts: 0, deletedOrReset: 1, skippedDrafts: 1 })
  await applyLearningRecovery(plan); expect(await db.pending.count()).toBe(0); expect(await db.drafts.count()).toBe(0)
  expect(await db.states.count()).toBe(0)
  expect(vi.mocked(postJson).mock.calls.every(([path]) => path === '/api/v1/learning/recovery-check')).toBe(true)
})
it('preserves existing pending, training queues and newer state; repeated import adds nothing', async () => {
  const f = await backup(); await applyLearningRecovery(await previewLearningRecovery(owner, f.text))
  const id = f.event.learningItemId, payload = { newer: true }
  await db.states.put({ accountKey: accountKey(owner), learningItemId: id, progressEpoch: f.event.progressEpoch, payload })
  const plan = await previewLearningRecovery(owner, f.text); expect(plan).toMatchObject({ pending: 0, existing: 1, drafts: 0, skippedDrafts: 1 })
  await applyLearningRecovery(plan); expect(await db.pending.count()).toBe(1); expect((await db.states.get([accountKey(owner), id]))!.payload).toEqual(payload)
})
it('rejects another account/origin, unsupported contracts, incomplete queues and prototype keys before requests', async () => {
  const f = await backup()
  expect(() => parseRecoveryBackup(f.text, other)).toThrow('同一账号 UUID')
  expect(() => parseRecoveryBackup(f.text, { ...owner, serverId: 'https://other.test' })).toThrow('同一服务器')
  const altered = JSON.parse(f.text); altered.local.tables.pending[0].results[0].typeId = 'UNKNOWN'
  await expect(previewLearningRecovery(owner, JSON.stringify(altered))).rejects.toThrow('格式')
  const malformed = JSON.parse(f.text); malformed.local.tables.drafts[0].payload.groups[0].retryQueue = []
  await expect(previewLearningRecovery(owner, JSON.stringify(malformed))).rejects.toThrow('格式')
  await expect(previewLearningRecovery(owner, f.text.replace('"application":', '"__proto__":{},"application":'))).rejects.toThrow('格式')
  expect(getJson).not.toHaveBeenCalled(); expect(postJson).not.toHaveBeenCalled()
})
it('does not mix data when the authenticated account changes or the check response omits requested identities', async () => {
  const f = await backup(); currentOwner = other.userId
  await expect(previewLearningRecovery(owner, f.text)).rejects.toThrow('登录账号已变化')
  currentOwner = owner.userId
  vi.mocked(postJson).mockResolvedValue({ state: { userId: owner.userId, items: [], books: [], missingItemIds: [], missingBookIds: [] }, events: [] })
  await expect(previewLearningRecovery(owner, f.text)).rejects.toThrow('不完整')
  expect(await db.pending.count()).toBe(0)
})
it('rechecks at confirmation and rejects changed results; a storage failure rolls back all restored tables', async () => {
  const f = await backup(), plan = await previewLearningRecovery(owner, f.text)
  deleted.add(f.event.learningItemId)
  await expect(applyLearningRecovery(plan)).rejects.toThrow('重新预检'); expect(await db.pending.count()).toBe(0)
  deleted.clear(); const retry = await previewLearningRecovery(owner, f.text)
  const fail = () => { throw new Error('quota unavailable') }; db.resources.hook('creating', fail)
  try { await expect(applyLearningRecovery(retry)).rejects.toThrow('quota unavailable') }
  finally { db.resources.hook('creating').unsubscribe(fail) }
  expect(await db.pending.count()).toBe(0); expect(await db.drafts.count()).toBe(0); expect(await db.states.count()).toBe(0)
  await applyLearningRecovery(retry); expect(await db.pending.count()).toBe(1)
})
it('retains original IDs and excludes conflicting accepted event contents rather than treating them as an acknowledgement', async () => {
  const f = await backup(); accepted.set(f.event.eventId, { ...f.event, attemptId: crypto.randomUUID() })
  const plan = await previewLearningRecovery(owner, f.text); expect(plan).toMatchObject({ pending: 0, conflicts: 1, drafts: 0 })
  expect(() => parseReview({ ...f.event, completedAt: '2026-02-30T01:00:00Z' })).toThrow('格式')
})
it('preserves version strings exactly for retries while comparing Java Instant timestamps independent of zero milliseconds', async () => {
  const f = await backup()
  const historical = { ...f.event, baseVersion: '2026-09-29T01:00:00Z', baseEventId: crypto.randomUUID() }
  expect(parseReview(historical).baseVersion).toBe('2026-09-29T01:00:00Z')
  expect(parseReview(f.event).submissionVersion).toBe('2026-09-30T01:00:00.000Z')
})
