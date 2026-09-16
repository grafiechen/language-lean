import type { AccountScope, CompletedReview } from './reviews'

/** 复习排程能力边界；具体 FSRS 实现及算法版本将在排程模块中选择。 */
export interface ReviewScheduler {
  readonly algorithmVersion: string
  schedule(state: unknown, review: CompletedReview): unknown
}
/** 音频读取能力；调用方无需关心音频来自本地缓存还是远端存储。 */
export interface AudioRepository {
  find(scope: AccountScope, versionId: string): Promise<Blob | undefined>
}
/** 待同步复习事件仓储，保证离线完成的进度可以稍后上传。 */
export interface PendingReviewStore {
  enqueue(scope: AccountScope, review: CompletedReview): Promise<void>
  list(scope: AccountScope): Promise<CompletedReview[]>
  acknowledge(scope: AccountScope, eventIds: string[]): Promise<void>
}
