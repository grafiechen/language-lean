import { aggregate, type Rating, type ReviewTrial, type ReviewTypeResult } from './reviews'

/** 一个训练批次开始时固定的题型协议快照。 */
export interface TrainingType {
  typeId: string
  contractVersion: number
}

/** 一个批次中的分组状态；每组最多包含十个不同的学习条目。 */
export interface TrainingGroup {
  groupId: string
  itemIds: string[]
  currentTypeIndex: number
  pendingQueue: string[]
  retryQueue: string[]
  passedLearningItemIds: string[]
}

/** 一个词条在当前批次中各题型的完整尝试记录。 */
export interface TrainingItemProgress {
  results: ReviewTypeResult[]
  completedAt?: string
}

/** 可直接保存到 IndexedDB 的训练批次草稿。 */
export interface TrainingBatch {
  schemaVersion: 1
  /** 草稿恢复保持原训练模式，切换入口不会改变正在进行的范围或读音规则。 */
  trainingMode?: 'REVIEW' | 'EXTRA' | 'EAR'
  batchId: string
  orderedReviewTypes: TrainingType[]
  groups: TrainingGroup[]
  currentGroupIndex: number
  items: Record<string, TrainingItemProgress>
}

/** 当前应呈现的题目；没有返回值表示整批已经完成。 */
export interface CurrentTrainingQuestion {
  groupId: string
  itemId: string
  type: TrainingType
}

const MAX_GROUP_SIZE = 10

/** 浏览器和测试环境都可用的本地草稿 ID。 */
function newId(prefix: string): string {
  const cryptoApi = globalThis.crypto
  return cryptoApi?.randomUUID
    ? cryptoApi.randomUUID()
    : `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

/**
 * 按服务端给出的优先顺序创建批次。
 *
 * <p>切分只发生在批次创建时，后续 Again 只操作当前分组的队列，不能把词条移到别的分组。</p>
 */
export function createTrainingBatch(
  itemIds: readonly string[],
  reviewTypes: readonly TrainingType[],
  batchId = newId('batch'),
): TrainingBatch {
  if (!itemIds.length || new Set(itemIds).size !== itemIds.length) throw new Error('Training items must be unique')
  if (!reviewTypes.length || reviewTypes.some(type => !type.typeId || !Number.isInteger(type.contractVersion) || type.contractVersion < 1))
    throw new Error('Training types are required')
  if (new Set(reviewTypes.map(type => type.typeId)).size !== reviewTypes.length)
    throw new Error('Training types must be unique')

  const groups: TrainingGroup[] = []
  for (let offset = 0; offset < itemIds.length; offset += MAX_GROUP_SIZE) {
    const groupItems = itemIds.slice(offset, offset + MAX_GROUP_SIZE)
    groups.push({
      groupId: newId('group'),
      itemIds: [...groupItems],
      currentTypeIndex: 0,
      pendingQueue: [...groupItems],
      retryQueue: [],
      passedLearningItemIds: [],
    })
  }
  const items: Record<string, TrainingItemProgress> = {}
  itemIds.forEach(itemId => { items[itemId] = { results: [] } })
  return {
    schemaVersion: 1,
    batchId,
    orderedReviewTypes: reviewTypes.map(type => ({ ...type })),
    groups,
    currentGroupIndex: 0,
    items,
  }
}

/** 把已经结束的分组推进掉，并返回当前分组。调用方不会得到空的活动分组。 */
function activeGroup(batch: TrainingBatch): TrainingGroup | undefined {
  while (batch.currentGroupIndex < batch.groups.length) {
    const group = batch.groups[batch.currentGroupIndex]
    if (group.currentTypeIndex < batch.orderedReviewTypes.length) return group
    batch.currentGroupIndex += 1
  }
  return undefined
}

/** 返回下一道题，题型阶段优先于同一词条的其他题型。 */
export function currentTrainingQuestion(batch: TrainingBatch): CurrentTrainingQuestion | null {
  const group = activeGroup(batch)
  if (!group) return null
  const itemId = group.pendingQueue[0] ?? group.retryQueue[0]
  if (!itemId) throw new Error('Active training group has no queued item')
  return { groupId: group.groupId, itemId, type: batch.orderedReviewTypes[group.currentTypeIndex] }
}

/** 在当前题型阶段追加一次判定，并按 Again 队尾规则推进队列。 */
export function recordTrainingRating(
  batch: TrainingBatch,
  itemId: string,
  rating: Rating,
  ratedAt = new Date().toISOString(),
  trialId = newId('trial'),
): TrainingBatch {
  if (!['AGAIN', 'HARD', 'GOOD'].includes(rating) || !trialId || !Number.isFinite(Date.parse(ratedAt)))
    throw new Error('Invalid training trial')
  const trials = Object.values(batch.items).flatMap(item => item.results.flatMap(result => result.trials))
  if (trials.some(trial => trial.id === trialId)) throw new Error('Duplicate trial ID')
  if (trials.some(trial => Date.parse(trial.ratedAt) > Date.parse(ratedAt))) throw new Error('Training time moved backwards')
  // 在副本上校验并推进；失败时不移出原队列，也不留下半条尝试记录。
  const updated = JSON.parse(JSON.stringify(batch)) as TrainingBatch
  applyTrainingRating(updated, itemId, rating, ratedAt, trialId)
  Object.assign(batch, updated)
  return batch
}

/** 仅在完整通过校验的副本上推进队列，成功后由外层一次性替换原批次。 */
function applyTrainingRating(batch: TrainingBatch, itemId: string, rating: Rating, ratedAt: string, trialId: string): void {
  const question = currentTrainingQuestion(batch)
  if (!question || question.itemId !== itemId) throw new Error('The submitted item is not the current question')

  const group = batch.groups[batch.currentGroupIndex]
  const queue = group.pendingQueue.length ? group.pendingQueue : group.retryQueue
  if (queue.shift() !== itemId) throw new Error('Training queue changed before rating')

  const progress = batch.items[itemId]
  if (!progress) throw new Error('Unknown training item')
  let result = progress.results.find(value => value.typeId === question.type.typeId)
  if (!result) {
    result = { typeId: question.type.typeId, contractVersion: question.type.contractVersion, trials: [] }
    progress.results.push(result)
  }
  if (result.contractVersion !== question.type.contractVersion) throw new Error('Training type version changed')
  const trial: ReviewTrial = { id: trialId, ratedAt, rating }
  result.trials.push(trial)

  if (rating === 'AGAIN') group.retryQueue.push(itemId)
  else if (!group.passedLearningItemIds.includes(itemId)) group.passedLearningItemIds.push(itemId)

  // 最后必做题型通过的当下即完成此词；不等待同组其他词结束或错词重试。
  if (rating !== 'AGAIN' && group.currentTypeIndex === batch.orderedReviewTypes.length - 1) {
    if (aggregate(batch.orderedReviewTypes.map(type => type.typeId), progress.results) === null)
      throw new Error('Required training type is incomplete')
    progress.completedAt = ratedAt
  }

  if (!group.pendingQueue.length && !group.retryQueue.length) {
    if (group.passedLearningItemIds.length !== group.itemIds.length)
      throw new Error('A training group cannot advance before every item passes')
    const nextType = group.currentTypeIndex + 1
    if (nextType < batch.orderedReviewTypes.length) {
      group.currentTypeIndex = nextType
      group.pendingQueue = [...group.itemIds]
      group.retryQueue = []
      group.passedLearningItemIds = []
    } else {
      group.currentTypeIndex = batch.orderedReviewTypes.length
    }
  }
  activeGroup(batch)
}

/** 判断某个词条是否已经完成本轮全部题型；完成后才能生成上传事件。 */
export function isTrainingItemComplete(batch: TrainingBatch, itemId: string): boolean {
  const progress = batch.items[itemId]
  if (!progress?.completedAt) return false
  return aggregate(batch.orderedReviewTypes.map(type => type.typeId), progress.results) !== null
}

/** 取得已完成词条的最终评分；未完成时返回 null。 */
export function trainingItemFinalRating(batch: TrainingBatch, itemId: string): Rating | null {
  const progress = batch.items[itemId]
  if (!progress?.completedAt) return null
  return aggregate(batch.orderedReviewTypes.map(type => type.typeId), progress.results)
}
