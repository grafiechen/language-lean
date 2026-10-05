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
  it('rejects unknown ratings, duplicate trials and attempts appended after passing', () => {
    const trial = { id: '1', ratedAt: '2026-09-12T00:00:00Z', rating: 'GOOD' as const }
    const result = { typeId: 'A', contractVersion: 1, trials: [trial] }
    expect(() => aggregate(['A'], [{ ...result, trials: [{ ...trial, rating: 'EASY' as never }] }])).toThrow()
    expect(() => aggregate(['A'], [{ ...result, trials: [trial, trial] }])).toThrow()
    expect(() => aggregate(['A'], [{ ...result, trials: [trial, { ...trial, id: '2' }] }])).toThrow()
  })
})
