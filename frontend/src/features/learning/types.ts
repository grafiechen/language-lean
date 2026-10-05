import type { FsrsProfile } from '../../core/fsrs'

/** 服务端返回的个人单词本摘要。 */
export interface Wordbook {
  id: string
  name: string
  description: string
  itemCount: number
  createdAt: string
  updatedAt: string
}
/** 单词本中的共享学习条目及 FSRS 调度状态。 */
export interface LearningItem {
  id: string
  dictionaryEntryId: string | null
  personalCustomEntryId?: string | null
  written: string
  languageCode: string
  status: string
  currentRevision: number
  manualEarFocus: boolean
  progressEpoch: string
  fsrsAlgorithmVersion: string
  reviewCount: number
  lapseCount: number
  lastReviewedAt: string | null
  nextReviewAt: string | null
  due: boolean
  progressVersion: string
  lastReviewEventId: string | null
  automaticEarFocus: boolean
  /** 与进度版本独立；旧缓存缺省为零，个人内容变动只使下载快照需要更新。 */
  personalContentRevision?: number
  /** 私人发音结构版本变更撤销旧训练资源，不删除已完成事件。 */
  personalAudioRevision?: number
  /** 老缓存允许缺少这两个字段；重新联网准备后才启用完整离线调度。 */
  fsrsState?: string
  scheduler?: FsrsProfile
  pendingSchedule?: 'PROJECTED' | 'UNAVAILABLE'
}

/** 服务端确认的当前进度；迟到事件也返回最新卡片状态。 */
export interface ReviewReceipt {
  status: 'APPLIED' | 'HISTORICAL' | 'DUPLICATE'
  eventId: string
  learningItemId: string
  progressEpoch: string
  progressVersion: string
  lastReviewEventId: string | null
  fsrsAlgorithmVersion: string
  fsrsState: string
  lastReviewedAt: string
  nextReviewAt: string
  reviewCount: number
  lapseCount: number
  automaticEarFocus: boolean
}

/** 最近完整复习记录，与中途尝试分开显示。 */
export interface ReviewHistory {
  eventId: string
  completedAt: string
  receivedAt: string
  finalRating: 'AGAIN' | 'HARD' | 'GOOD'
  baseVersion: string
  algorithmVersion: string
  nextReviewAt: string
}
/** 用于页面显示的状态文本，封禁词条必须保留明确提示。 */
export function learningStatusLabel(status: string): string {
  return ({ PUBLISHED: '已发布', PRIVATE: '私有词条', UNAVAILABLE: '暂不可用', BANNED: '已封禁', DRAFT: '草稿' } as Record<string, string>)[status] ?? status
}
/** 私有与公开的可训练范围一致，封禁及禁用语言明确排除。 */
export function trainableStatus(status: string): boolean { return status === 'PUBLISHED' || status === 'PRIVATE' }
