import { describe, expect, it } from 'vitest'
import {
  createTrainingBatch,
  currentTrainingQuestion,
  isTrainingItemComplete,
  recordTrainingRating,
  trainingItemFinalRating,
} from './training'

describe('training queue', () => {
  it('splits the server order into groups of at most ten', () => {
    const batch = createTrainingBatch(
      Array.from({ length: 23 }, (_, index) => `item-${index}`),
      [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }],
      'batch-1',
    )
    expect(batch.groups.map(group => group.itemIds.length)).toEqual([10, 10, 3])
    expect(currentTrainingQuestion(batch)?.itemId).toBe('item-0')
  })

  it('moves Again to the queue tail and keeps it in the final worst rating', () => {
    const batch = createTrainingBatch(['a', 'b'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }], 'batch-2')
    recordTrainingRating(batch, 'a', 'AGAIN', '2026-10-01T00:00:00Z', 'trial-a-1')
    expect(currentTrainingQuestion(batch)?.itemId).toBe('b')
    recordTrainingRating(batch, 'b', 'GOOD', '2026-10-01T00:01:00Z', 'trial-b-1')
    expect(isTrainingItemComplete(batch, 'b')).toBe(true)
    expect(batch.items.b.completedAt).toBe('2026-10-01T00:01:00Z')
    expect(isTrainingItemComplete(batch, 'a')).toBe(false)
    expect(currentTrainingQuestion(batch)?.itemId).toBe('a')
    recordTrainingRating(batch, 'a', 'HARD', '2026-10-01T00:02:00Z', 'trial-a-2')
    expect(isTrainingItemComplete(batch, 'a')).toBe(true)
    expect(trainingItemFinalRating(batch, 'a')).toBe('AGAIN')
    expect(currentTrainingQuestion(batch)).toBe(null)
    expect(batch.items.b.completedAt).toBe('2026-10-01T00:01:00Z')
  })

  it('finishes one type for the whole group before starting the next type', () => {
    const batch = createTrainingBatch(['a', 'b'], [
      { typeId: 'A', contractVersion: 1 },
      { typeId: 'B', contractVersion: 1 },
    ], 'batch-3')
    recordTrainingRating(batch, 'a', 'GOOD', '2026-10-01T00:00:00Z', 'a-a')
    expect(currentTrainingQuestion(batch)?.itemId).toBe('b')
    expect(currentTrainingQuestion(batch)?.type.typeId).toBe('A')
    recordTrainingRating(batch, 'b', 'GOOD', '2026-10-01T00:01:00Z', 'b-a')
    expect(currentTrainingQuestion(batch)?.itemId).toBe('a')
    expect(currentTrainingQuestion(batch)?.type.typeId).toBe('B')
    expect(isTrainingItemComplete(batch, 'a')).toBe(false)
    recordTrainingRating(batch, 'a', 'GOOD', '2026-10-01T00:02:00Z', 'a-b')
    expect(isTrainingItemComplete(batch, 'a')).toBe(true)
    expect(isTrainingItemComplete(batch, 'b')).toBe(false)
    recordTrainingRating(batch, 'b', 'GOOD', '2026-10-01T00:03:00Z', 'b-b')
    expect(isTrainingItemComplete(batch, 'a')).toBe(true)
    expect(currentTrainingQuestion(batch)).toBe(null)
  })

  it('rejects a stale rating for a different current item', () => {
    const batch = createTrainingBatch(['a', 'b'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }], 'batch-4')
    expect(() => recordTrainingRating(batch, 'b', 'GOOD')).toThrow('current question')
  })

  it('leaves the original queue untouched when a trial or contract is invalid', () => {
    const batch = createTrainingBatch(['a'], [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
    const before = JSON.stringify(batch)
    expect(() => recordTrainingRating(batch, 'a', 'EASY' as never)).toThrow()
    expect(() => recordTrainingRating(batch, 'a', 'GOOD', 'bad-time')).toThrow()
    expect(JSON.stringify(batch)).toBe(before)
    batch.items.a.results.push({ typeId: 'LISTEN_RECALL', contractVersion: 2, trials: [] })
    const broken = JSON.stringify(batch)
    expect(() => recordTrainingRating(batch, 'a', 'GOOD')).toThrow('version changed')
    expect(JSON.stringify(batch)).toBe(broken)
  })
})
