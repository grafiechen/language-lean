import { getJson, postJson } from '../../shared/api'
import type { AudioRequest } from './types'
import type { Results } from '../dictionary/types'

/** 公共发音反馈只带公开文本和主动填写的说明，不发送个人词条或进度。 */
export interface AudioFeedback {
  id: string; entryId: string; resourceId: string; kind: 'WORD' | 'EXAMPLE'
  category: 'WRONG_PRONUNCIATION' | 'UNPLAYABLE'; reportedRevision: number; reportedAudioVersionId: string | null
  pronunciationText: string; description: string; status: 'PENDING' | 'RESOLVED' | 'DISMISSED'
  resolutionNote: string; createdAt: string; reviewedAt: string | null; version: number
}
export interface AudioFeedbackDetail { report: AudioFeedback; written: string; currentPronunciation: string; currentRevision: number | null; available: boolean; currentAudioVersionId: string | null }
export const feedbackStatus = (status: AudioFeedback['status']) => ({ PENDING: '待处理', RESOLVED: '已修复', DISMISSED: '无需修复' })[status]
export const feedbackCategory = (category: AudioFeedback['category']) => ({ WRONG_PRONUNCIATION: '读音不正确', UNPLAYABLE: '无法播放或音频异常' })[category]
export interface FeedbackSubmission { id: string; entryId: string; resourceId: string; kind: AudioRequest['kind']; category: AudioFeedback['category']; pronunciationText: string; audioVersionId: string | null; description: string }

/** 冻结网络重试的提交正文；相同表单内容保留请求 ID，改动后产生新 ID。 */
export function feedbackSubmission(request: AudioRequest, category: AudioFeedback['category'], description: string, audioVersionId: string | null, previous?: FeedbackSubmission): FeedbackSubmission {
  if (request.scope !== 'PUBLISHED') throw new Error('发音反馈仅用于公共词条，请在个人内容中修正私人发音。')
  const value = { entryId: request.entryId, resourceId: request.resourceId, kind: request.kind, category, pronunciationText: request.pronunciationText.trim(), audioVersionId, description: description.trim() }
  if (!value.pronunciationText || !value.description || value.description.length > 1000) throw new Error('请填写发音问题说明，最多1000字。')
  if (previous && Object.entries(value).every(([key, content]) => previous[key as keyof FeedbackSubmission] === content)) return previous
  return Object.freeze({ id: crypto.randomUUID(), ...value })
}
/** 本人列表按当前公开资源筛选，分页由服务端执行。 */
export function ownFeedback(request: AudioRequest, page = 0) {
  return getJson<Results<AudioFeedback>>('/api/v1/audio-feedback?' + new URLSearchParams({ entryId: request.entryId, resourceId: request.resourceId, kind: request.kind, page: String(page) }))
}
/** 显式账号头防止浏览器共享 Cookie 在提交期间切换了学习账号。 */
export function submitFeedback(owner: string, submission: FeedbackSubmission) {
  return postJson<AudioFeedback>('/api/v1/audio-feedback', submission, { 'X-Learning-Account': owner })
}
