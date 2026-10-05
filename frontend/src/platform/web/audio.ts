import { apiFetch, postJson } from '../../shared/api'
import type { AccountScope } from '../../core/reviews'
import type { AudioRequest, AudioResult } from '../../features/audio/types'
import { accountKey, requireLiveCache } from './database'
import { learningDatabase } from './reviewSync'

const running = new Map<string, Promise<AudioResult>>()
/** 资源键含权限范围，不因文本相同而跨词条误用音频。 */
export function audioRequestKey(request: AudioRequest): string {
  return JSON.stringify([request.entryId, request.scope, request.kind, request.resourceId])
}
/** 发音输入用与后端一致的 UTF-8 SHA-256，不把旧音频配到新内容。 */
export async function pronunciationHash(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text.trim()))
  return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('')
}
/** 同资源同账号的详情和播放请求共用任务；仅管理员显式操作允许强制新版本。 */
export function ensureAudio(scope: AccountScope, request: AudioRequest, force = false): Promise<AudioResult> {
  const key = JSON.stringify([accountKey(scope), request, force])
  const previous = running.get(key)
  if (previous) return previous
  const task = generate(request, force).finally(() => running.delete(key))
  running.set(key, task)
  return task
}
/** 生成中的任务有界等待；超时保留 PENDING，由用户明确重试。 */
async function generate(request: AudioRequest, force: boolean): Promise<AudioResult> {
  if (!request.pronunciationText.trim()) return { status: 'MISSING_PRONUNCIATION', audioVersionId: null,
    url: null, stale: false, textHash: null, message: '尚未填写发音，请先补充发音文本。' }
  if (!navigator.onLine) throw new Error('当前离线，只能播放已经缓存的音频。')
  const query = new URLSearchParams({ kind: request.kind, scope: request.scope })
  const base = (force && !['PERSONAL', 'OVERRIDE'].includes(request.scope) ? '/api/v1/admin/audio' : '/api/v1/audio') + '/entries/' + request.entryId + '/resources/' + request.resourceId
  let result = await postJson<AudioResult>(base + (force ? '/regenerate?' : '/ensure?') + query)
  for (let attempt = 0; result.status === 'PENDING' && attempt < 20; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 1000))
    result = await postJson<AudioResult>('/api/v1/audio/entries/' + request.entryId + '/resources/' + request.resourceId + '/ensure?' + query)
  }
  return result
}
/** 缓存文件必须与正在显示的发音一致，生成失败不能使用旧版本完成训练。 */
export async function cacheReadyAudio(scope: AccountScope, request: AudioRequest, result: AudioResult, refresh = false): Promise<Blob> {
  if (result.status !== 'READY' || !result.audioVersionId || !result.url)
    throw new Error(result.message || '音频尚未就绪，请重试。')
  if (result.stale || result.textHash !== await pronunciationHash(request.pronunciationText))
    throw new Error('音频与当前发音不一致，请重新打开词条。')
  const key = accountKey(scope)
  const cached = await learningDatabase.audio.get([key, result.audioVersionId])
  if (!refresh && cached?.textHash === result.textHash && cached.requestKey === audioRequestKey(request)) {
    // 另一类型的编辑可推进结构版本而沿用同一发音文件，更新核对元信息避免反复失效。
    if (cached.audioRevision !== request.audioRevision)
      await learningDatabase.audio.update([key, result.audioVersionId], { audioRevision: request.audioRevision })
    return cached.blob
  }
  const response = await apiFetch(result.url)
  if (!response.ok) throw new Error('音频读取失败，请重试补生成。')
  const blob = await response.blob()
  if (!blob.size || blob.size > 5_000_000) throw new Error('音频文件大小无效。')
  const readyVersion = result.audioVersionId, readyHash = result.textHash
  await learningDatabase.transaction('rw', learningDatabase.audio, learningDatabase.syncMeta, async () => {
  await requireLiveCache(learningDatabase, scope)
  await learningDatabase.audio.put({ accountKey: key, audioVersionId: readyVersion, requestKey: audioRequestKey(request),
    cachedAt: new Date().toISOString(), textHash: readyHash, blob, audioRevision: request.audioRevision })
  })
  return blob
}
/** 离线读取固定版本；缺失时不能偷偷改成另一版本或在线生成。 */
export async function cachedAudio(scope: AccountScope, versionId: string): Promise<Blob | undefined> {
  return (await learningDatabase.audio.get([accountKey(scope), versionId]))?.blob
}
