import 'fake-indexeddb/auto'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { LearningDatabase, accountKey } from './database'
import { cacheWordbookMetadata } from './wordbookMetadata'
import { createTrainingBatch } from '../../core/training'
import type { Wordbook } from '../../features/learning/types'

let db: LearningDatabase
const owner = { serverId: 'https://learn.test', userId: 'owner' }, other = { ...owner, userId: 'other' }
const book: Wordbook = { id: 'book', name: '原名', description: '原说明', itemCount: 1, createdAt: '2026-10-01', updatedAt: '2026-10-01', version: 0 }
beforeEach(() => { db = new LearningDatabase('book-metadata-' + crypto.randomUUID()) })
afterEach(() => db.delete())
async function prepare(scope = owner) {
  await db.preparations.put({ accountKey: accountKey(scope), wordbookId: book.id, book, preparedAt: '2026-10-01', status: 'READY', words: [], items: [], missingPronunciation: 0, failures: [] })
}
it('updates only this account metadata and preserves learning state, drafts and prepared membership', async () => {
  await prepare(); await prepare(other)
  const key = accountKey(owner)
  const batch = createTrainingBatch(['item'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }], 'batch')
  await db.drafts.put({ accountKey: key, batchId: 'batch', schemaVersion: 1, payload: batch })
  await db.states.put({ accountKey: key, learningItemId: 'item', progressEpoch: 'epoch', payload: { reviewCount: 3 } })
  await cacheWordbookMetadata(owner, { ...book, name: '新名', description: '新说明', version: 1, itemCount: 999 }, db)
  const row = (await db.preparations.get([key, book.id]))!
  expect(row.book.name).toBe('新名'); expect(row.book.description).toBe('新说明'); expect(row.book.itemCount).toBe(1)
  expect(row.status).toBe('READY'); expect(row.preparedAt).toBe('2026-10-01')
  expect((await db.preparations.get([accountKey(other), book.id]))!.book.name).toBe('原名')
  expect((await db.drafts.get([key, 'batch']))!.payload).toEqual(batch)
  expect((await db.states.get([key, 'item']))!.payload).toEqual({ reviewCount: 3 })
  expect((await db.syncMeta.get(key))!.revision).toBe(1)
})
it('does not replace newer metadata with delayed responses or create unprepared snapshots', async () => {
  await prepare(); await cacheWordbookMetadata(owner, { ...book, name: '最新版', version: 2 }, db)
  await cacheWordbookMetadata(owner, { ...book, name: '迟到旧版', version: 1 }, db)
  expect((await db.preparations.get([accountKey(owner), book.id]))!.book.name).toBe('最新版')
  await cacheWordbookMetadata(owner, { ...book, id: 'unprepared' }, db)
  expect(await db.preparations.get([accountKey(owner), 'unprepared'])).toBeUndefined()
})
it('rejects retired accounts without restoring their metadata', async () => {
  await prepare(); await db.syncMeta.put({ accountKey: accountKey(owner), revision: 1, retired: true })
  await expect(cacheWordbookMetadata(owner, { ...book, name: '旧账号新名字', version: 1 }, db)).rejects.toThrow('注销')
  expect((await db.preparations.get([accountKey(owner), book.id]))!.book.name).toBe('原名')
})
