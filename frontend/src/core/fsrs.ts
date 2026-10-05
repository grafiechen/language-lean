/**
 * java-fsrs 1.0.0 的浏览器适配，保持状态机、整日取整及学习步骤一致。
 * Adapted from https://github.com/open-spaced-repetition/java-fsrs/tree/v1.0.0
 * Copyright (c) 2025 Open Spaced Repetition. MIT license: /third-party/java-fsrs-LICENSE.txt.
 * 服务器仍负责正式调度；本模块只对不可变的本次基准生成离线预估。
 */
import type { Rating } from './reviews'

export const fsrsVersion = 'FSRS-6/java-fsrs-1.0.0'
/** 由服务器随学习条目提供并固定到训练基准中的算法参数。 */
export interface FsrsProfile {
  schemaVersion: number; algorithmVersion: string; desiredRetention: number; maximumIntervalDays: number
  parameters: number[]; learningStepsMillis: number[]; relearningStepsMillis: number[]; enableFuzzing: boolean
}
/** 与 Java Card JSON 一致，不混入复习次数或业务词条身份。 */
export interface FsrsCard {
  cardId: number; state: 'LEARNING' | 'REVIEW' | 'RELEARNING'; step: number | null
  stability: number | null; difficulty: number | null; due: string; lastReview: string | null
}
const day = 86400000
const clampDifficulty = (value: number) => Math.min(Math.max(value, 1), 10)
/** 不支持的算法版本、随机扰动或损坏参数不能伪装为准确的离线周期。 */
export function validateFsrsProfile(profile: FsrsProfile): void {
  if (profile.schemaVersion !== 1 || profile.algorithmVersion !== fsrsVersion || profile.enableFuzzing
    || profile.parameters?.length !== 21 || profile.parameters.some(value => !Number.isFinite(value))
    || profile.parameters[20] <= 0 || !Number.isFinite(profile.desiredRetention)
    || profile.desiredRetention <= 0 || profile.desiredRetention >= 1
    || !Number.isSafeInteger(profile.maximumIntervalDays) || profile.maximumIntervalDays < 1
    || !Array.isArray(profile.learningStepsMillis) || !Array.isArray(profile.relearningStepsMillis)
    || [...profile.learningStepsMillis, ...profile.relearningStepsMillis].some(value => !Number.isSafeInteger(value) || value < 0))
    throw new Error('离线调度参数不兼容，请联网重新准备。')
}
/** UUID.hashCode 与 Java 保持一致；算法中的整数 cardId 不替代业务全局身份。 */
function cardId(id: string): number {
  const hex = id.replaceAll('-', '')
  if (!/^[0-9a-f]{32}$/i.test(hex)) throw new Error('学习身份无效。')
  return [0, 8, 16, 24].reduce((hash, offset) => hash ^ parseInt(hex.slice(offset, offset + 8), 16), 0)
}
/** 纯计算：只使用已固定的卡片、最终最差评分和实际完成时间。 */
export function scheduleOffline(itemId: string, baseline: string, rating: Rating, completedAt: string, profile: FsrsProfile): FsrsCard {
  validateFsrsProfile(profile)
  const time = Date.parse(completedAt), grade = { AGAIN: 1, HARD: 2, GOOD: 3 }[rating]
  if (!Number.isFinite(time) || !grade) throw new Error('离线调度时间或评分无效。')
  const card: FsrsCard = baseline === '{}' ? { cardId: cardId(itemId), state: 'LEARNING', step: 0,
    stability: null, difficulty: null, due: completedAt, lastReview: null } : JSON.parse(baseline)
  if (!['LEARNING', 'REVIEW', 'RELEARNING'].includes(card.state)
    || !Number.isInteger(card.cardId) || !Number.isFinite(Date.parse(card.due))
    || card.lastReview !== null && (!Number.isFinite(Date.parse(card.lastReview)) || Date.parse(card.lastReview) > time)
    || card.state !== 'REVIEW' && (!Number.isInteger(card.step) || card.step! < 0)
    || (card.stability === null || card.difficulty === null)
      && !(card.state === 'LEARNING' && card.stability === null && card.difficulty === null && card.lastReview === null)
    || card.stability !== null && (!Number.isFinite(card.stability) || card.stability < 0.001)
    || card.difficulty !== null && (!Number.isFinite(card.difficulty) || card.difficulty < 1 || card.difficulty > 10))
    throw new Error('离线卡片状态不完整，请联网重新准备。')
  const w = profile.parameters, decay = -w[20], factor = Math.pow(0.9, 1 / decay) - 1
  const initialDifficulty = (value: number) => clampDifficulty(w[4] - Math.pow(Math.E, w[5] * (value - 1)) + 1)
  const elapsedDays = card.lastReview === null ? null : Math.trunc((time - Date.parse(card.lastReview)) / day)
  if (card.stability === null && card.difficulty === null) {
    card.stability = Math.max(w[grade - 1], 0.001); card.difficulty = initialDifficulty(grade)
  } else {
    const stability = card.stability!, difficulty = card.difficulty!
    if (elapsedDays !== null && elapsedDays < 1) {
      let increase = Math.exp(w[17] * (grade - 3 + w[18])) * Math.pow(stability, -w[19])
      if (rating === 'GOOD') increase = Math.max(increase, 1)
      card.stability = Math.max(stability * increase, 0.001)
    } else {
      const retrievability = card.lastReview === null ? 0 : Math.pow(1 + factor * Math.max(0, elapsedDays!) / stability, decay)
      card.stability = Math.max(rating === 'AGAIN'
        ? Math.min(w[11] * Math.pow(difficulty, -w[12]) * (Math.pow(stability + 1, w[13]) - 1)
          * Math.exp((1 - retrievability) * w[14]), stability / Math.exp(w[17] * w[18]))
        : stability * (1 + Math.exp(w[8]) * (11 - difficulty) * Math.pow(stability, -w[9])
          * (Math.exp((1 - retrievability) * w[10]) - 1) * (rating === 'HARD' ? w[15] : 1)), 0.001)
    }
    card.difficulty = clampDifficulty(w[7] * initialDifficulty(4)
      + (1 - w[7]) * (difficulty + (10 - difficulty) * (-w[6] * (grade - 3)) / 9))
  }
  const longInterval = () => Math.min(Math.max(Math.round(card.stability! / factor
    * (Math.pow(profile.desiredRetention, 1 / decay) - 1)), 1), profile.maximumIntervalDays) * day
  let interval: number
  if (card.state === 'REVIEW') {
    if (rating === 'AGAIN' && profile.relearningStepsMillis.length) {
      card.state = 'RELEARNING'; card.step = 0; interval = profile.relearningStepsMillis[0]
    } else interval = longInterval()
  } else {
    const steps = card.state === 'LEARNING' ? profile.learningStepsMillis : profile.relearningStepsMillis
    if (!steps.length || card.step! >= steps.length && rating !== 'AGAIN'
      || rating === 'GOOD' && card.step! + 1 === steps.length) {
      card.state = 'REVIEW'; card.step = null; interval = longInterval()
    } else if (rating === 'AGAIN') { card.step = 0; interval = steps[0] }
    else if (rating === 'HARD') {
      // 保持 java-fsrs 1.0.0 多步重新学习分支的兼容行为；当前服务端默认只有一个重新学习步骤。
      interval = card.step === 0 ? Math.round(steps.length === 1 ? steps[0] * 1.5
        : (profile.learningStepsMillis[0] + profile.learningStepsMillis[1]) / 2) : steps[card.step!]
    } else { card.step = card.step! + 1; interval = steps[card.step] }
  }
  if (!Number.isFinite(interval) || interval < 0 || !Number.isFinite(card.stability) || !Number.isFinite(card.difficulty))
    throw new Error('离线调度结果无效，请联网重新准备。')
  card.due = new Date(time + interval).toISOString(); card.lastReview = new Date(time).toISOString()
  return card
}
