import type { AccountScope } from '../../core/reviews'
import { createTrainingBatch, currentTrainingQuestion, type TrainingBatch } from '../../core/training'
import { getJson } from '../../shared/api'
import { learningAudioSource, type LearningContent } from '../../features/learning/personalContent'
import type { LearningItem } from '../../features/learning/types'
import { trainableStatus } from '../../features/learning/types'
import type { TrainingWord } from '../../features/learning/trainingTypes'
import { accountKey, bumpCacheRevision, DexieTrainingDraftStore } from './database'
import { learningDatabase, reviewSessions } from './reviewSync'
import { cacheReadyAudio, ensureAudio } from './audio'
import { learningStorageError } from './cacheManagement'

/** 部分失败时不开始训练，不暗中跳过有发音但资源未就绪的词。 */
export interface PreparedTraining { words: TrainingWord[]; contents?: LearningContent[]; missingPronunciation: number; failures: string[] }
/** 读取到期队列或额外训练范围，逐项准备内容和音频。 */
export async function prepareTraining(scope: AccountScope, wordbookId: string, extra: boolean,
  progress: (done: number, total: number) => void, options: { earFocus?: boolean; allReadings?: boolean } = {}): Promise<PreparedTraining> {
  const revision = (await learningDatabase.syncMeta.get(accountKey(scope)))?.revision ?? 0
  const items = await getJson<LearningItem[]>('/api/v1/learning/wordbooks/' + wordbookId + (extra ? '/items' : '/review-queue'))
  const languages = await getJson<{ code: string; reviewTypes: { typeId: string; contractVersion: number }[] }[]>('/api/v1/languages')
  const result: PreparedTraining = { words: [], contents: [], missingPronunciation: 0, failures: [] }
  const available = items.filter(item => trainableStatus(item.status) && (!options.earFocus || item.manualEarFocus || item.automaticEarFocus))
  for (let index = 0; index < available.length; index++) {
    const learning = available[index]
    try {
      if (!languages.find(language => language.code === learning.languageCode)?.reviewTypes
        .some(type => type.typeId === 'LISTEN_RECALL' && type.contractVersion === 1))
        throw new Error('该语言的听音回忆未启用或题型版本不兼容。')
      const content = await getJson<LearningContent>('/api/v1/learning/items/' + learning.id + '/content')
      if (content.learningItemId !== learning.id || content.entry.id !== (learning.dictionaryEntryId ?? learning.personalCustomEntryId))
        throw new Error('学习内容归属不一致，请重新准备。')
      const entry = content.entry
      if (!trainableStatus(entry.status) || !entry.content) throw new Error('词条内容已封禁或不可用。')
      learning.currentRevision = entry.currentRevision; learning.personalContentRevision = content.personal.revision
      learning.personalAudioRevision = content.personal.audioRevision ?? 0
      result.contents!.push(content)
      const reading = entry.content?.readings.find(value => value.pronunciationText.trim())
      if (!reading) result.missingPronunciation++
      else {
        const request = { ...learningAudioSource(content, 'WORD'), resourceId: reading.id, kind: 'WORD' as const, pronunciationText: reading.pronunciationText }
        const audio = await ensureAudio(scope, request)
        await cacheReadyAudio(scope, request, audio)
        const readingAudio = [{ readingId: reading.id, audioVersionId: audio.audioVersionId!, textHash: audio.textHash! }]
        if (options.allReadings) for (const alternative of entry.content!.readings.filter(value => value.id !== reading.id && value.pronunciationText.trim())) {
          const nextRequest = { ...learningAudioSource(content, 'WORD'), resourceId: alternative.id, kind: 'WORD' as const, pronunciationText: alternative.pronunciationText }
          const next = await ensureAudio(scope, nextRequest); await cacheReadyAudio(scope, nextRequest, next)
          readingAudio.push({ readingId: alternative.id, audioVersionId: next.audioVersionId!, textHash: next.textHash! })
        }
        result.words.push({ learning, entry, personal: content.personal, readingId: reading.id, audioVersionId: audio.audioVersionId!, textHash: audio.textHash!, cacheRevision: revision,
          ...(options.allReadings ? { readingAudio } : {}) })
      }
    } catch (cause) { result.failures.push(learning.written + '：' + learningStorageError(cause, '资源准备失败')) }
    progress(index + 1, available.length)
  }
  if (((await learningDatabase.syncMeta.get(accountKey(scope)))?.revision ?? 0) !== revision)
    throw new Error('下载期间学习缓存已变化，请重新准备。')
  return result
}
/** 资源和草稿同一事务落盘，防止退出后出现缺少内容的半个批次。 */
export async function startTraining(scope: AccountScope, wordbookId: string, words: TrainingWord[], trainingMode: 'REVIEW' | 'EXTRA' | 'EAR' = 'REVIEW'): Promise<TrainingBatch> {
  const batch = createTrainingBatch(words.map(word => word.learning.id), [{ typeId: 'LISTEN_RECALL', contractVersion: 1 }])
  batch.trainingMode = trainingMode
  const baselines = Object.fromEntries(words.map(word => [word.learning.id, { progressEpoch: word.learning.progressEpoch,
    progressVersion: word.learning.progressVersion, lastReviewEventId: word.learning.lastReviewEventId,
    fsrsState: word.learning.fsrsState, scheduler: word.learning.scheduler }]))
  await learningDatabase.transaction('rw', learningDatabase.resources, learningDatabase.drafts, learningDatabase.syncMeta, async () => {
    const revision = (await learningDatabase.syncMeta.get(accountKey(scope)))?.revision ?? 0
    if (words.some(word => word.cacheRevision !== undefined && word.cacheRevision !== revision))
      throw new Error('准备好的下载内容已变化，请重新准备后开始训练。')
    await reviewSessions.start(scope, batch, baselines)
    await learningDatabase.resources.add({ accountKey: accountKey(scope), batchId: batch.batchId, wordbookId,
      createdAt: new Date().toISOString(), items: JSON.parse(JSON.stringify(Object.fromEntries(words.map(word => [word.learning.id, word])))) as Record<string, TrainingWord> })
  })
  return batch
}

/** 只重取当前题音频，不清空已缓存的判定或改变队列。内容变化时拒绝配错音频。 */
export async function refreshTrainingAudio(scope: AccountScope, batchId: string, itemId: string, readingId?: string): Promise<TrainingWord> {
  const key = accountKey(scope)
  const row = await learningDatabase.resources.get([key, batchId])
  const word = row?.items[itemId]
  const reading = word?.entry.content?.readings.find(value => value.id === (readingId ?? word.readingId))
  if (!word || !reading) throw new Error('训练资源已失效，请返回单词本重新准备。')
  const personal = word.personal ?? { revision: 0, meaningOverride: null, notes: '', tags: [], updatedAt: null }
  const request = { ...learningAudioSource({ learningItemId: itemId, entry: word.entry, personal }, 'WORD'), resourceId: reading.id, kind: 'WORD' as const, pronunciationText: reading.pronunciationText }
  const result = await ensureAudio(scope, request)
  await cacheReadyAudio(scope, request, result, true)
  return learningDatabase.transaction('rw', learningDatabase.resources, learningDatabase.syncMeta, async () => {
    if ((await learningDatabase.syncMeta.get(key))?.retired) throw new Error('账号已注销，不能恢复训练音频。')
    const current = await learningDatabase.resources.get([key, batchId])
    if (!current?.items[itemId]) throw new Error('训练资源已失效，请返回单词本重新准备。')
    const updated = { ...current.items[itemId] }
    if (reading.id === updated.readingId) { updated.audioVersionId = result.audioVersionId!; updated.textHash = result.textHash! }
    if (updated.readingAudio) updated.readingAudio = updated.readingAudio.map(audio => audio.readingId === reading.id
      ? { readingId: reading.id, audioVersionId: result.audioVersionId!, textHash: result.textHash! } : audio)
    current.items[itemId] = updated
    await learningDatabase.resources.put(current)
    return updated
  })
}

/** 删除/重置只清理对应账号失效代际；音频缓存与其他词已完成事件保留。 */
export async function invalidateLearningProgress(scope: AccountScope, epochs: Record<string, string | null>): Promise<void> {
  const key = accountKey(scope)
  const invalid = (id: string, epoch: string) => Object.hasOwn(epochs, id) && epochs[id] !== epoch
  await learningDatabase.transaction('rw', [learningDatabase.pending, learningDatabase.drafts,
    learningDatabase.resources, learningDatabase.states, learningDatabase.issues, learningDatabase.syncMeta, learningDatabase.projections], async () => {
      await bumpCacheRevision(learningDatabase, scope)
      for (const event of await learningDatabase.pending.where('accountKey').equals(key).toArray())
        if (invalid(event.learningItemId, event.progressEpoch)) await learningDatabase.pending.delete([key, event.eventId])
      for (const state of await learningDatabase.states.where('accountKey').equals(key).toArray())
        if (invalid(state.learningItemId, state.progressEpoch)) await learningDatabase.states.delete([key, state.learningItemId])
      for (const issue of await learningDatabase.issues.where('accountKey').equals(key).toArray())
        if (invalid(issue.learningItemId, issue.progressEpoch)) await learningDatabase.issues.delete([key, issue.eventId])
      for (const projection of await learningDatabase.projections.where('accountKey').equals(key).toArray())
        if (invalid(projection.learningItemId, projection.progressEpoch)) await learningDatabase.projections.delete([key, projection.eventId])
      for (const resource of await learningDatabase.resources.where('accountKey').equals(key).toArray()) {
        if (Object.values(resource.items).some(word => invalid(word.learning.id, word.learning.progressEpoch))) {
          await learningDatabase.drafts.delete([key, resource.batchId])
          await learningDatabase.resources.delete([key, resource.batchId])
        }
      }
    })
}
/** 恢复当前账号当前单词本的草稿，已提交完的批次不重新练习。 */
export async function restoreTraining(scope: AccountScope, wordbookId: string): Promise<{ batch: TrainingBatch; words: Record<string, TrainingWord> } | null> {
  const resources = await learningDatabase.resources.where('accountKey').equals(accountKey(scope))
    .filter(row => row.wordbookId === wordbookId).toArray()
  resources.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  const drafts = new DexieTrainingDraftStore(learningDatabase)
  for (const row of resources) {
    const batch = await drafts.load(scope, row.batchId)
    // 已完成但尚未上传的批次保留确认关联，不阻止离线到期后开始新一轮。
    if (batch && currentTrainingQuestion(batch)) return { batch, words: row.items }
  }
  return null
}
