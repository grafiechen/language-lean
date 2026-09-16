import { describe, expect, it } from 'vitest'
import { aggregate } from './reviews'
describe('review completion', () => {
  it('waits for every type to pass, preserving failure after retry', () => {
    const a = { typeId: 'A', contractVersion: 1, trials: [{ id: '1', ratedAt: '2026-09-12T00:00:00Z', rating: 'AGAIN' as const }] }
    expect(aggregate(['A'], [a])).toBeNull()
    const passed = { ...a, trials: [...a.trials, { id: '2', ratedAt: '2026-09-12T00:01:00Z', rating: 'GOOD' as const }] }
    expect(aggregate(['A', 'B'], [passed])).toBeNull()
    expect(aggregate(['A'], [passed])).toBe('AGAIN')
  })
})
