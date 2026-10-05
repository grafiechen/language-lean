import { getJson, postJson } from '../../shared/api'
import type { CurrentUser } from '../../features/auth/store'
import type { AccountScope } from '../../core/reviews'
import type { Rating } from '../../core/reviews'
import { ReviewUploader } from '../../features/learning/sync'
import { DexiePendingReviewStore, DexieReviewSessionStore, LearningDatabase } from './database'
import { reconcileLearning } from './reconciliation'
import { recordSyncFailure } from './syncIssues'

/** 浏览器统一复用学习数据库；账号分区包含站点来源和稳定的用户 UUID。 */
export const learningDatabase = new LearningDatabase()
export const pendingReviews = new DexiePendingReviewStore(learningDatabase)
export const reviewSessions = new DexieReviewSessionStore(learningDatabase)
/** 在线时重新向服务器验证会话归属，不能仅依据可能已过期的界面缓存。 */
export async function currentReviewScope(): Promise<AccountScope> {
  const user = await getJson<CurrentUser>('/api/v1/auth/me')
  return { serverId: window.location.origin, userId: user.id }
}
/** 每词完成后和用户点击补传时均调用这个单例上传器。 */
export const reviewUploader = new ReviewUploader({
  currentScope: currentReviewScope,
  pending: scope => pendingReviews.list(scope),
  submit: event => postJson('/api/v1/learning/reviews', event),
  acknowledge: (scope, receipt) => reviewSessions.acknowledge(scope, receipt),
  reconcile: reconcileLearning,
  failed: recordSyncFailure,
})

/** 训练适配器统一调用：先持久化本次判定，词条完成后立即触发后台补传。 */
export async function recordBrowserTrainingRating(scope: AccountScope, batchId: string, itemId: string, rating: Rating) {
  const saved = await reviewSessions.rate(scope, batchId, itemId, rating)
  const upload = saved.event ? reviewUploader.upload(scope) : undefined
  // 网络失败不取消已保存的答题；上传结果由页面状态或手动补传入口反馈。
  void upload?.catch(() => undefined)
  return { ...saved, upload }
}
