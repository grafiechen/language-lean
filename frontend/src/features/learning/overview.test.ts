import { expect, it } from 'vitest'
import type { LearningItem } from './types'
import { matchesLearningFilter, summarizeLearning } from './overview'

/** 只设置影响学习分组的字段，验证发布状态、到期时间和共享身份的边界。 */
function item(id: string, overrides: Partial<LearningItem> = {}): LearningItem {
  return { id, dictionaryEntryId: id, written: '猫', languageCode: 'ja', status: 'PUBLISHED', currentRevision: 1,
    manualEarFocus: false, progressEpoch: 'epoch', fsrsAlgorithmVersion: 'FSRS', reviewCount: 0, lapseCount: 0,
    lastReviewedAt: null, nextReviewAt: null, due: true, progressVersion: '0', lastReviewEventId: null,
    automaticEarFocus: false, ...overrides }
}
const now = new Date(2026, 9, 3, 12).getTime()

it('counts a shared word once and separates new words from due reviews', () => {
  const reviewed = item('shared', { reviewCount: 1, lastReviewedAt: new Date(now - 1000).toISOString(), nextReviewAt: new Date(now - 500).toISOString() })
  const summary = summarizeLearning([reviewed, reviewed, item('new'), item('banned', { status: 'BANNED' })], now)
  expect(summary).toEqual({ total: 3, learned: 1, fresh: 1, due: 1, today: 1 })
})

it('does not offer future, banned or unknown pending schedules as due reviews', () => {
  const reviewed = { reviewCount: 2, lastReviewedAt: new Date(now - 86400000).toISOString() }
  expect(matchesLearningFilter(item('future', { ...reviewed, due: true, nextReviewAt: new Date(now + 1000).toISOString() }), 'due', now)).toBe(false)
  expect(matchesLearningFilter(item('pending', { ...reviewed, pendingSchedule: 'UNAVAILABLE' }), 'due', now)).toBe(false)
  expect(matchesLearningFilter(item('banned', { ...reviewed, status: 'BANNED' }), 'due', now)).toBe(false)
  expect(matchesLearningFilter(item('private', { ...reviewed, status: 'PRIVATE' }), 'due', now)).toBe(true)
  expect(matchesLearningFilter(item('focus', { status: 'BANNED', manualEarFocus: true }), 'focus', now)).toBe(true)
})
