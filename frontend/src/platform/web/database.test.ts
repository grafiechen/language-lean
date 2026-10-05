import 'fake-indexeddb/auto'
import Dexie from 'dexie'
import { expect, it } from 'vitest'
import { LearningDatabase, DexiePendingReviewStore, DexieTrainingDraftStore, DexieReviewSessionStore } from './database'
import { createTrainingBatch } from '../../core/training'
import type { ReviewReceipt } from '../../features/learning/types'

it('upgrades the previous cache schema without losing pending events or audio', async () => {
  const name = 'upgrade-' + crypto.randomUUID(), old = new Dexie(name), upgraded = new LearningDatabase(name)
  old.version(1).stores({ pending: '[accountKey+eventId], accountKey', drafts: '[accountKey+batchId], accountKey', states: '[accountKey+learningItemId], accountKey' })
  old.version(2).stores({ audio: '[accountKey+audioVersionId], accountKey', resources: '[accountKey+batchId], accountKey' })
  old.version(3).stores({ accounts: 'accountKey, serverId', preparations: '[accountKey+wordbookId], accountKey' })
  old.version(4).stores({ issues: '[accountKey+eventId], accountKey', syncMeta: 'accountKey' })
  try {
    await old.table('pending').add({ accountKey: 'A', eventId: 'offline-event', learningItemId: 'word' })
    await old.table('audio').add({ accountKey: 'A', audioVersionId: 'saved-audio', blob: new Blob(['saved sound']) })
    await old.table('issues').add({ accountKey: 'A', eventId: 'offline-event', message: '保留原冲突' })
    old.close(); await upgraded.open()
    expect((await upgraded.pending.toArray())[0].eventId).toBe('offline-event')
    expect(await (await upgraded.audio.get(['A', 'saved-audio']))!.blob.text()).toBe('saved sound')
    expect(await upgraded.accounts.count()).toBe(0); expect(await upgraded.preparations.count()).toBe(0)
    expect((await upgraded.issues.get(['A', 'offline-event']))?.message).toBe('保留原冲突')
    expect(await upgraded.projections.count()).toBe(0)
  } finally { old.close(); await upgraded.delete() }
})
it('isolates accounts and clears only explicitly acknowledged events', async () => {
  const db = new LearningDatabase('test-' + crypto.randomUUID())
  const store = new DexiePendingReviewStore(db)
  const a = { serverId: 'server', userId: 'A' }
  const b = { serverId: 'server', userId: 'B' }
  const event = { eventId: 'same-id', attemptId: 'attempt', learningItemId: 'item', progressEpoch: 'epoch',
    baseVersion: '0', submissionVersion: '1', completedAt: '2026-09-12T00:00:00Z', results: [] }
  try {
    await store.enqueue(a, event)
    await store.enqueue(b, event)
    await store.enqueue(a, event)
    expect(await store.list(a)).toHaveLength(1)
    await store.acknowledge(a, ['same-id'])
    expect(await store.list(a)).toHaveLength(0)
    expect(await store.list(b)).toHaveLength(1)
  } finally { await db.delete() }
})

it('saves every rating and enqueues each completed word before the whole group ends', async () => {
  const db = new LearningDatabase('session-' + crypto.randomUUID())
  const sessions = new DexieReviewSessionStore(db)
  const drafts = new DexieTrainingDraftStore(db)
  const pending = new DexiePendingReviewStore(db)
  const scope = { serverId: 'server', userId: 'A' }
  const batch = createTrainingBatch(['a', 'b'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
  const baseline = { progressEpoch: 'epoch', progressVersion: '0', lastReviewEventId: null }
  try {
    await sessions.start(scope, batch, { a: baseline, b: baseline })
    await sessions.rate(scope, batch.batchId, 'a', 'AGAIN', '2026-10-01T00:00:00Z')
    expect((await drafts.load(scope, batch.batchId))?.groups[0].retryQueue).toEqual(['a'])
    await drafts.save(scope, (await drafts.load(scope, batch.batchId))!)
    expect(await pending.list(scope)).toHaveLength(0)
    const completedB = await sessions.rate(scope, batch.batchId, 'b', 'GOOD', '2026-10-01T00:01:00Z')
    expect(completedB.event?.completedAt).toBe('2026-10-01T00:01:00Z')
    expect(await pending.list(scope)).toHaveLength(1)
    const receipt = (event: NonNullable<typeof completedB.event>): ReviewReceipt => ({
      status: 'APPLIED', eventId: event.eventId, learningItemId: event.learningItemId, progressEpoch: event.progressEpoch,
      progressVersion: event.completedAt, lastReviewEventId: event.eventId, fsrsAlgorithmVersion: 'test', fsrsState: '{}',
      lastReviewedAt: event.completedAt, nextReviewAt: event.completedAt, reviewCount: 1, lapseCount: 0, automaticEarFocus: false,
    })
    await sessions.acknowledge(scope, receipt(completedB.event!))
    const restored = await new DexieTrainingDraftStore(db).load(scope, batch.batchId)
    expect(restored?.items.b).toBeUndefined()
    expect(restored?.items.a.results[0].trials[0].rating).toBe('AGAIN')
    const completedA = await sessions.rate(scope, batch.batchId, 'a', 'GOOD', '2026-10-01T00:02:00Z')
    expect(completedA.event?.results[0].trials.map(trial => trial.rating)).toEqual(['AGAIN', 'GOOD'])
    await sessions.acknowledge(scope, receipt(completedA.event!))
    expect(await pending.list(scope)).toHaveLength(0)
    expect(await drafts.load(scope, batch.batchId)).toBeUndefined()
  } finally { await db.delete() }
})

it('rolls back both the queue and event when persisting a rating fails', async () => {
  const db = new LearningDatabase('rollback-' + crypto.randomUUID())
  const sessions = new DexieReviewSessionStore(db)
  const scope = { serverId: 'server', userId: 'A' }
  const batch = createTrainingBatch(['a'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
  try {
    await sessions.start(scope, batch, { a: { progressEpoch: 'epoch', progressVersion: '0', lastReviewEventId: null } })
    db.drafts.hook('updating', () => { throw new Error('disk write failed') })
    await expect(sessions.rate(scope, batch.batchId, 'a', 'GOOD')).rejects.toThrow('disk write failed')
    expect(await new DexiePendingReviewStore(db).list(scope)).toHaveLength(0)
    expect((await new DexieTrainingDraftStore(db).load(scope, batch.batchId))?.groups[0].pendingQueue).toEqual(['a'])
  } finally { await db.delete() }
})

it('isolates training drafts by account and removes only the acknowledged draft', async () => {
  const db = new LearningDatabase('draft-' + crypto.randomUUID())
  const store = new DexieTrainingDraftStore(db)
  const a = { serverId: 'server', userId: 'A' }
  const b = { serverId: 'server', userId: 'B' }
  const draftA = createTrainingBatch(['item-a'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }], 'shared-batch')
  const draftB = createTrainingBatch(['item-b'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }], 'shared-batch')
  try {
    await store.save(a, draftA)
    await store.save(b, draftB)
    expect((await store.load(a, 'shared-batch'))?.items['item-a']).toBeDefined()
    expect(await store.load(a, 'missing')).toBeUndefined()
    await store.remove(a, 'shared-batch')
    expect(await store.load(a, 'shared-batch')).toBeUndefined()
    expect((await store.load(b, 'shared-batch'))?.items['item-b']).toBeDefined()
  } finally { await db.delete() }
})
