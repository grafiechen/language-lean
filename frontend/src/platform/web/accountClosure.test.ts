import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { createTrainingBatch } from '../../core/training'
import { accountKey, DexieTrainingDraftStore } from './database'
import { learningDatabase as db, pendingReviews, reviewSessions } from './reviewSync'
import { rememberAccount, availableCachedAccounts } from './cachedAccounts'
import { retireAccount } from './accountClosure'
import { cacheReadyAudio, pronunciationHash } from './audio'

const owner = { serverId: 'server', userId: 'old-uuid' }, other = { serverId: 'server', userId: 'other-uuid' }
const batch = () => createTrainingBatch(['item'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
const baseline = { item: { progressEpoch: 'epoch', progressVersion: '0', lastReviewEventId: null } }
/** 每个表放入两个账号的记录，验证清理边界覆盖全部 IndexedDB 表。 */
async function seed() {
  for (const scope of [owner, other]) for (const table of db.tables) await table.put({
    accountKey: accountKey(scope), eventId: 'event', batchId: 'batch', learningItemId: 'item',
    audioVersionId: 'audio', wordbookId: 'book', revision: 4, username: 'same-name', privateBody: 'private',
  })
}
beforeEach(async () => {
  vi.restoreAllMocks(); vi.unstubAllGlobals()
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', { getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) })
  await Promise.all(db.tables.map(table => table.clear()))
})
afterAll(async () => { vi.unstubAllGlobals(); await db.delete() })

it('purges all closed-account tables and selection while preserving other accounts and an opaque isolation marker', async () => {
  await seed(); localStorage.setItem('language-lean.active-account', accountKey(owner))
  await retireAccount(owner)
  for (const table of db.tables) {
    expect(await table.where('accountKey').equals(accountKey(other)).count()).toBe(1)
    expect(await table.where('accountKey').equals(accountKey(owner)).count()).toBe(table.name === 'syncMeta' ? 1 : 0)
  }
  expect(await db.syncMeta.get(accountKey(owner))).toEqual({ accountKey: accountKey(owner), revision: 5, retired: true })
  expect(localStorage.getItem('language-lean.active-account')).toBeNull()
  expect(localStorage.getItem('language-lean.explicit-logout')).toBe('true')
})
it('blocks late training, ratings and identity writes; the same display name with a new UUID starts cleanly', async () => {
  const active = batch(); await reviewSessions.start(owner, active, baseline); await retireAccount(owner)
  await expect(reviewSessions.rate(owner, active.batchId, 'item', 'GOOD')).rejects.toThrow('账号已注销')
  await expect(reviewSessions.start(owner, batch(), baseline)).rejects.toThrow('账号已注销')
  await expect(new DexieTrainingDraftStore(db).save(owner, batch())).rejects.toThrow('账号已注销')
  await expect(rememberAccount(owner, 'same-name')).rejects.toThrow('账号已注销')
  await expect(pendingReviews.enqueue(owner, { eventId: 'late-event' } as never)).rejects.toThrow('账号已注销')
  const replacement = { ...owner, userId: 'new-uuid' }
  await rememberAccount(replacement, 'same-name'); await reviewSessions.start(replacement, batch(), baseline)
  expect(await pendingReviews.list(replacement)).toEqual([])
  expect(await db.drafts.where('accountKey').equals(accountKey(owner)).count()).toBe(0)
  expect(await db.drafts.where('accountKey').equals(accountKey(replacement)).count()).toBe(1)
})
it('rolls back failed deletion and permits retry without changing another-account selection', async () => {
  await seed(); localStorage.setItem('language-lean.active-account', accountKey(other))
  const fail = () => { throw new Error('disk unavailable') }; db.audio.hook('deleting', fail)
  try { await expect(retireAccount(owner)).rejects.toThrow('disk unavailable') }
  finally { db.audio.hook('deleting').unsubscribe(fail) }
  for (const table of db.tables) expect(await table.where('accountKey').equals(accountKey(owner)).count()).toBe(1)
  expect((await db.syncMeta.get(accountKey(owner)))?.retired).toBeUndefined()
  await expect(rememberAccount(owner, 'same-name')).rejects.toThrow('账号已注销')
  expect(await availableCachedAccounts(owner.serverId)).not.toContainEqual(expect.objectContaining({ accountKey: accountKey(owner) }))
  await retireAccount(owner)
  expect(localStorage.getItem('language-lean.active-account')).toBe(accountKey(other))
})
it('rejects an audio response that arrives after account deletion instead of restoring private audio', async () => {
  let release!: (value: Response) => void, started!: () => void
  const waiting = new Promise<void>(resolve => { started = resolve })
  vi.stubGlobal('fetch', () => { started(); return new Promise<Response>(resolve => { release = resolve }) })
  const request = { entryId: 'private', scope: 'PERSONAL' as const, kind: 'WORD' as const,
    resourceId: 'reading', pronunciationText: 'ねこ' }
  const running = cacheReadyAudio(owner, request, { status: 'READY', audioVersionId: 'late-version',
    url: '/api/v1/audio/versions/private', stale: false, textHash: await pronunciationHash('ねこ'), message: '' })
  const rejection = expect(running).rejects.toThrow('账号已注销')
  await waiting; await retireAccount(owner); release(new Response(new Blob(['private audio'])))
  await rejection; expect(await db.audio.count()).toBe(0)
})
