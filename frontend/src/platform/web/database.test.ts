import 'fake-indexeddb/auto'
import { expect, it } from 'vitest'
import { LearningDatabase, DexiePendingReviewStore } from './database'
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
