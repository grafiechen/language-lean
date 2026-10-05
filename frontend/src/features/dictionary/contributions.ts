import type { DictionaryContent } from './types'
import type { PrivateEntry } from '../learning/privateEntries'
import type { LearningContent } from '../learning/personalContent'

/** 投稿列表不携带私有正文，详情快照由独立端点读取。 */
export interface ContributionRow {
  id: string; written: string; languageCode: string; kind: string; status: string; createdAt: string
  publishedEntryId: string | null; publishedRevision: number | null
}
/** 明确提交的正文及提交时公开基准，不包含私人笔记和标签。 */
export interface ContributionView {
  row: ContributionRow; scriptCode: string; version: number; baseRevision: number | null
  content: DictionaryContent; baseContent: DictionaryContent | null; submitNote: string; reviewNote: string; reviewedAt: string | null
}
/** 前端提交只白名单复制内容；不能展开整个私人详情。 */
export interface ContributionRequest {
  id: string; privateEntryId: string | null; privateVersion: number | null
  learningItemId: string | null; personalRevision: number | null; baseRevision: number | null
  content: DictionaryContent; note: string; publishRequested: boolean
}
export interface ContributionCredit { sourceName: string; license: string; revision: number; publishedAt: string; contributorDeleted?: boolean }
export const contributionStatus = (s: string) => ({ PENDING_REVIEW: '待审核', APPROVED: '已审核公开', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }[s] ?? s)
export const contributionKind = (s: string) => ({ NEW_ENTRY: '新词条', SUPPLEMENT: '补充内容', REVISION: '内容修订' }[s] ?? s)
/** 只复制词典正文，用户在表单中逐项决定要公开的例句。 */
export function contributionDraft(source: PrivateEntry | LearningContent): Omit<ContributionRequest, 'id' | 'note' | 'publishRequested'> {
  if ('learningItemId' in source) {
    if (!source.entry.content || source.entry.originType === 'PRIVATE' || source.entry.status !== 'PUBLISHED') throw new Error('请选择可用的公开词条进行修订。')
    return { privateEntryId: null, privateVersion: null, learningItemId: source.learningItemId, personalRevision: source.personal.revision,
      baseRevision: source.entry.currentRevision, content: JSON.parse(JSON.stringify(source.entry.content)) as DictionaryContent }
  }
  return { privateEntryId: source.id, privateVersion: source.version, learningItemId: null, personalRevision: null,
    baseRevision: null, content: JSON.parse(JSON.stringify(source.content)) as DictionaryContent }
}
/** 相同内容的失败重试沿用UUID，编辑后必须换身份，避免服务端幂等冲突。 */
export function contributionRetry(body: Omit<ContributionRequest, 'id'>, previous: ContributionRequest | null): ContributionRequest {
  const { id: oldId, ...oldBody } = previous ?? { id: '' }
  const id = oldId && JSON.stringify(body) === JSON.stringify(oldBody) ? oldId : crypto.randomUUID()
  return JSON.parse(JSON.stringify({ id, ...body })) as ContributionRequest
}
