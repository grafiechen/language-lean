import type { AccountScope, CompletedReview } from '../../core/reviews'
import type { ReviewReceipt } from './types'

/** 自动上传和手动补传使用同一网络/存储协议，便于离线与账号切换测试。 */
export interface ReviewSyncPorts {
  currentScope(): Promise<AccountScope | null>
  pending(scope: AccountScope): Promise<CompletedReview[]>
  submit(event: CompletedReview): Promise<ReviewReceipt>
  acknowledge(scope: AccountScope, receipt: ReviewReceipt): Promise<void>
  reconcile?(scope: AccountScope): Promise<{ removed: number }>
  failed?(scope: AccountScope, event: CompletedReview, cause: unknown): Promise<void>
}
/** 补传结果；失败事件保留本地，不把未确认记录当成成功。 */
export interface SyncResult { uploaded: number; remaining: number; failed: number; stopped: boolean; removed?: number }

/** 一个账号同时只运行一次补传；每个请求前重新检查当前登录归属。 */
export class ReviewUploader {
  private readonly running = new Map<string, Promise<SyncResult>>()
  constructor(private readonly ports: ReviewSyncPorts) {}

  /** 按答题时间发送全部积压记录，确保离线版本链的基准事件先上传。 */
  upload(scope: AccountScope): Promise<SyncResult> {
    const key = JSON.stringify([scope.serverId, scope.userId])
    const previous = this.running.get(key)
    if (previous) return previous
    const promise = this.run(scope).finally(() => { this.running.delete(key) })
    this.running.set(key, promise)
    return promise
  }

  /** 切换账号、会话过期或断网时停止；业务冲突留下待处理记录，并尝试其他独立词。 */
  private async run(scope: AccountScope): Promise<SyncResult> {
    let uploaded = 0, failed = 0, stopped = false
    let removed = 0
    if (this.ports.reconcile) {
      try {
        const current = await this.ports.currentScope()
        if (current?.userId !== scope.userId || current.serverId !== scope.serverId) stopped = true
        else removed = (await this.ports.reconcile(scope)).removed
      } catch { stopped = true }
    }
    const attempted = new Set<string>()
    while (!stopped) {
      // 上传期间可能继续完成下一个词；本次任务继续排空新增事件，但不无限重试失败事件。
      const pending = (await this.ports.pending(scope)).filter(event => !attempted.has(event.eventId)).sort((a, b) =>
        Date.parse(a.completedAt) - Date.parse(b.completedAt) || a.eventId.localeCompare(b.eventId))
      if (!pending.length) break
      for (const event of pending) {
        attempted.add(event.eventId)
        try {
          // 核对可能撤销了旧事件，不能使用前一次队列快照继续发送。
          if (!(await this.ports.pending(scope)).some(value => value.eventId === event.eventId)) continue
          const current = await this.ports.currentScope()
          if (current?.userId !== scope.userId || current.serverId !== scope.serverId) { stopped = true; break }
          const receipt = await this.ports.submit(event)
          await this.ports.acknowledge(scope, receipt)
          uploaded++
        } catch (cause) {
          failed++
          const status = (cause as { status?: number })?.status
          if (status && status >= 400 && status < 500 && status !== 401 && status !== 403)
            await this.ports.failed?.(scope, event, cause)
          if (!status || status === 401 || status === 403 || status >= 500) { stopped = true; break }
        }
      }
    }
    return { uploaded, failed, stopped, remaining: (await this.ports.pending(scope)).length,
      ...(this.ports.reconcile ? { removed } : {}) }
  }
}
