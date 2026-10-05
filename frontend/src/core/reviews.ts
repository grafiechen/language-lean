/** 用户对一次复习题的掌握程度；AGAIN 表示本题型仍需穿插重试。 */
export type Rating = 'AGAIN' | 'HARD' | 'GOOD'
/** 单个题型的一次作答，保留完整重试过程。 */
export interface ReviewTrial { id: string; ratedAt: string; rating: Rating }
/** 一个词条在某个题型下的作答结果。 */
export interface ReviewTypeResult {
  typeId: string
  contractVersion: number
  trials: ReviewTrial[]
}
/** 标识离线数据归属的服务端和账号，防止多账号数据混用。 */
export interface AccountScope { serverId: string; userId: string }
/** 某个词条完成本轮全部题型后生成的可上传复习事件。 */
export interface CompletedReview {
  eventId: string
  attemptId: string
  learningItemId: string
  progressEpoch: string
  baseVersion: string
  baseEventId?: string | null
  submissionVersion: string
  completedAt: string
  results: ReviewTypeResult[]
}
/** 可扩展题型的能力声明；渲染与媒体细节由具体适配器负责。 */
export interface ReviewTypeAdapter {
  readonly typeId: string
  readonly contractVersion: number
  readonly supportsOffline: boolean
  // 题型适配器只声明渲染与媒体需求，排程和上传由外层流程统一处理。
  requiredResources: readonly ('ENTRY' | 'AUDIO')[]
}
/** 第一版启用的听音回忆题型。 */
export const listenRecall: ReviewTypeAdapter = {
  typeId: 'LISTEN_RECALL', contractVersion: 1, supportsOffline: true,
  requiredResources: ['ENTRY', 'AUDIO'],
}
/** 当前客户端支持的题型注册表。 */
export const reviewTypes = new Map([[listenRecall.typeId, listenRecall]])

/** 校验全部必做题型并取最差评分；尚未通过任一题型时返回 null。 */
export function aggregate(requiredTypes: readonly string[], results: readonly ReviewTypeResult[]): Rating | null {
  if (!requiredTypes.length || new Set(requiredTypes).size !== requiredTypes.length) throw new Error('Invalid required types')
  if (results.some(r => !requiredTypes.includes(r.typeId)) || new Set(results.map(r => r.typeId)).size !== results.length)
    throw new Error('Unexpected or duplicate type result')
  const ratings: Rating[] = []
  const trialIds = new Set<string>()
  for (const result of results) {
    if (!Number.isInteger(result.contractVersion) || result.contractVersion < 1) throw new Error('Invalid type version')
    let previous = -Infinity
    result.trials.forEach((trial, index) => {
      const time = Date.parse(trial.ratedAt)
      if (!trial.id || trialIds.has(trial.id) || !Number.isFinite(time) || time < previous
        || !['AGAIN', 'HARD', 'GOOD'].includes(trial.rating)) throw new Error('Invalid review trial')
      if (index < result.trials.length - 1 && trial.rating !== 'AGAIN') throw new Error('Trial appended after passing')
      trialIds.add(trial.id)
      previous = time
    })
  }
  for (const type of requiredTypes) {
    const result = results.find(r => r.typeId === type)
    if (!result?.trials.length || result.trials[result.trials.length - 1].rating === 'AGAIN') return null
    ratings.push(...result.trials.map(t => t.rating))
  }
  return ratings.includes('AGAIN') ? 'AGAIN' : ratings.includes('HARD') ? 'HARD' : 'GOOD'
}
