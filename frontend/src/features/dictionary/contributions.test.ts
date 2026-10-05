import { describe, expect, it } from 'vitest'
import { contributionDraft, contributionRetry } from './contributions'
import { emptyContent } from './types'
import type { LearningContent } from '../learning/personalContent'
describe('明确投稿与网络重试', () => {
  it('只提交明确正文，不夹带学习笔记、标签或进度，并与源对象隔离', () => {
    const source: LearningContent = { learningItemId: crypto.randomUUID(), entry: { id: crypto.randomUUID(), written: '猫', languageCode: 'ja', scriptCode: 'Jpan', status: 'PUBLISHED', originType: 'OPEN_SOURCE', currentRevision: 3, hasDraft: false, content: emptyContent() },
      personal: { revision: 5, meaningOverride: null, notes: '秘密笔记', tags: ['秘密标签'], updatedAt: null } }
    const draft = contributionDraft(source)
    expect(draft.baseRevision).toBe(3); expect(draft.personalRevision).toBe(5)
    expect(JSON.stringify(draft)).not.toContain('秘密'); draft.content.senses[0]!.gloss = '公开解释'
    expect(source.entry.content!.senses[0]!.gloss).toBe('')
  })
  it('失败重试相同内容沿用UUID，编辑内容或许可后更换UUID', () => {
    const body = { privateEntryId: crypto.randomUUID(), privateVersion: 1, learningItemId: null, personalRevision: null, baseRevision: null, content: emptyContent(), note: '投稿', publishRequested: true }
    const first = contributionRetry(body, null)
    expect(contributionRetry(body, first).id).toBe(first.id)
    body.content.license = '新许可'
    expect(contributionRetry(body, first).id).not.toBe(first.id)
    expect(first.content.license).toBe('')
  })
  it('私有词条来源版本独立，不能从封禁或私人学习包装绕过来源类型', () => {
    const source = { id: crypto.randomUUID(), languageCode: 'ja', scriptCode: 'Jpan', written: '私词', content: emptyContent(), version: 8, createdAt: '', updatedAt: '' }
    expect(contributionDraft(source).privateVersion).toBe(8)
    const bad = { learningItemId: crypto.randomUUID(), entry: { originType: 'PRIVATE', status: 'PRIVATE', content: source.content } } as LearningContent
    expect(() => contributionDraft(bad)).toThrow()
  })
})
