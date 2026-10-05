import { expect, it } from 'vitest'
import { scheduleOffline, type FsrsCard, type FsrsProfile } from './fsrs'
import type { Rating } from './reviews'
import fixtures from './fsrs.fixtures.json'

/** 固定数据由官方 Java 库独立生成；普通测试不更新任何预期值。 */
it('matches 108 official Java vectors including short-term, review, relearning and alternate settings', () => {
  expect(fixtures.length).toBe(108)
  const rolling = new Map<string, FsrsCard>()
  for (const row of fixtures) {
    const actual = scheduleOffline(row.itemId, row.baseline, row.rating as Rating, row.completedAt, row.profile)
    const expected = row.stateAfter as FsrsCard
    expect(actual.state, row.label).toBe(expected.state); expect(actual.step, row.label).toBe(expected.step)
    expect(actual.cardId, row.label).toBe(expected.cardId)
    expect(Date.parse(actual.due), row.label).toBe(Date.parse(expected.due))
    expect(Date.parse(actual.lastReview!), row.label).toBe(Date.parse(expected.lastReview!))
    expect(actual.stability, row.label).toBeCloseTo(expected.stability!, 10)
    expect(actual.difficulty, row.label).toBeCloseTo(expected.difficulty!, 10)
    const chain = row.label.slice(0, row.label.lastIndexOf('/'))
    const chained = scheduleOffline(row.itemId, rolling.has(chain) ? JSON.stringify(rolling.get(chain)) : '{}',
      row.rating as Rating, row.completedAt, row.profile)
    expect(Date.parse(chained.due), row.label + '/continuous').toBe(Date.parse(expected.due))
    expect(chained.stability, row.label + '/continuous').toBeCloseTo(expected.stability!, 10)
    rolling.set(chain, chained)
  }
})
it('does not mutate the fixed baseline or deployment parameters', () => {
  const row = fixtures[1], before = JSON.stringify(row)
  scheduleOffline(row.itemId, row.baseline, row.rating as Rating, row.completedAt, row.profile)
  expect(JSON.stringify(row)).toBe(before)
})
it('refuses unknown algorithms, fuzzing, corrupt parameters and incomplete cards', () => {
  const row = fixtures[0]
  for (const change of [{ algorithmVersion: 'future' }, { enableFuzzing: true }, { desiredRetention: 1 }, { parameters: [] }])
    expect(() => scheduleOffline(row.itemId, '{}', 'GOOD', row.completedAt, { ...row.profile, ...change } as FsrsProfile)).toThrow('不兼容')
  expect(() => scheduleOffline(row.itemId, '{"state":"REVIEW"}', 'GOOD', row.completedAt, row.profile)).toThrow('状态不完整')
  expect(() => scheduleOffline(row.itemId, fixtures[1].baseline, 'GOOD', '2024-01-01T00:00:00Z', row.profile)).toThrow('状态不完整')
})
