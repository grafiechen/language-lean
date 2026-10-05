import type { DictionaryContent, PublicEntry } from '../dictionary/types'
import type { AudioScope } from '../audio/types'

/** 仅本人可读的学习内容，不属于公开词典。 */
export interface PersonalContent {
  revision: number; meaningOverride: string | null; notes: string; tags: string[]; updatedAt: string | null
  readingsOverride?: DictionaryContent['readings'] | null; sensesOverride?: DictionaryContent['senses'] | null; audioRevision?: number
}
/** 一次接口响应同时固定公开版与个人有效内容，防止训练使用不一致的快照。 */
export interface LearningContent { learningItemId: string; entry: PublicEntry; personal: PersonalContent }
/** 编辑表单必须携带独立内容基准，清除个人释义时传 null。 */
export interface SavePersonalContent { expectedRevision: number; meaningOverride: string | null; notes: string; tags: string[];
  replaceStructuredContent?: boolean; readingsOverride?: DictionaryContent['readings'] | null; sensesOverride?: DictionaryContent['senses'] | null }
/** 根据资源类型选择本人覆盖或原始发音，未覆盖资源沿用公共音频。 */
export function learningAudioSource(content: LearningContent, kind: 'WORD' | 'EXAMPLE'): { entryId: string; scope: AudioScope; audioRevision?: number } {
  const overridden = kind === 'WORD' ? content.personal.readingsOverride != null : content.personal.sensesOverride != null
  return overridden ? { entryId: content.learningItemId, scope: 'OVERRIDE', audioRevision: content.personal.audioRevision ?? 0 }
    : { entryId: content.entry.id, scope: content.entry.originType === 'PRIVATE' ? 'PERSONAL' : 'PUBLISHED' }
}
/** 兼容中英文逗号与换行；服务端继续执行数量、长度及去重校验。 */
export function parsePersonalTags(text: string): string[] {
  return [...new Set(text.split(/[,，\n]/).map(value => value.trim()).filter(Boolean))]
}
