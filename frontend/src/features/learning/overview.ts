import type { LearningItem } from './types'
import { trainableStatus } from './types'

export type LearningFilter = 'all' | 'new' | 'due' | 'learning' | 'focus'

/** 学习阶段与词典发布状态独立；待确认周期不能冒充到期，封禁词仍能在全部列表查看。 */
export function matchesLearningFilter(item: LearningItem, filter: LearningFilter, now = Date.now()): boolean {
  if (filter === 'all') return true
  if (filter === 'focus') return item.manualEarFocus || item.automaticEarFocus
  if (!trainableStatus(item.status)) return false
  const fresh = item.reviewCount === 0 && !item.lastReviewedAt
  if (filter === 'new') return fresh
  if (filter === 'learning') return !fresh
  return !fresh && item.pendingSchedule !== 'UNAVAILABLE'
    && (item.nextReviewAt ? Date.parse(item.nextReviewAt) <= now : item.due)
}

/** 用稳定学习 ID 去重，今日统计按浏览器本地日期，不设置配额或永久掌握阈值。 */
export function summarizeLearning(items: readonly LearningItem[], now = Date.now()) {
  const unique = [...new Map(items.map(item => [item.id, item])).values()]
  const today = new Date(now).toDateString()
  return {
    total: unique.length,
    learned: unique.filter(item => item.reviewCount > 0 || item.lastReviewedAt).length,
    fresh: unique.filter(item => matchesLearningFilter(item, 'new', now)).length,
    due: unique.filter(item => matchesLearningFilter(item, 'due', now)).length,
    today: unique.filter(item => item.lastReviewedAt && new Date(item.lastReviewedAt).toDateString() === today).length,
  }
}
