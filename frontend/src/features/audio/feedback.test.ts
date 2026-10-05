import { describe, expect, it } from 'vitest'
import { feedbackSubmission } from './feedback'
import type { AudioRequest } from './types'
const request: AudioRequest = { entryId: crypto.randomUUID(), resourceId: crypto.randomUUID(), kind: 'WORD', scope: 'PUBLISHED', pronunciationText: '  ねこ  ' }
describe('公共发音反馈提交', () => {
  it('网络重试复用原请求 ID，输入或音频版本改变后重新提交', () => {
    const first = feedbackSubmission(request, 'WRONG_PRONUNCIATION', '  声调不对  ', null)
    expect(feedbackSubmission(request, 'WRONG_PRONUNCIATION', '声调不对', null, first)).toBe(first)
    expect(feedbackSubmission(request, 'UNPLAYABLE', '声调不对', null, first).id).not.toBe(first.id)
    expect(feedbackSubmission(request, 'WRONG_PRONUNCIATION', '声调不对', crypto.randomUUID(), first).id).not.toBe(first.id)
    expect(Object.isFrozen(first)).toBe(true)
  })
  it('拒绝私人内容、空发音及无效说明，提交正文没有学习进度', () => {
    for (const scope of ['DRAFT', 'PERSONAL', 'OVERRIDE'] as const) expect(() => feedbackSubmission({ ...request, scope }, 'UNPLAYABLE', '异常', null)).toThrow('仅用于公共词条')
    expect(() => feedbackSubmission({ ...request, pronunciationText: '' }, 'UNPLAYABLE', '异常', null)).toThrow()
    expect(() => feedbackSubmission(request, 'UNPLAYABLE', ' '.repeat(3), null)).toThrow()
    expect(() => feedbackSubmission(request, 'UNPLAYABLE', '长'.repeat(1001), null)).toThrow()
    const value = feedbackSubmission({ ...request, audioRevision: 9 }, 'UNPLAYABLE', '无法播放', null)
    expect(value).not.toHaveProperty('audioRevision'); expect(value).not.toHaveProperty('scope')
    expect(value.pronunciationText).toBe('ねこ')
  })
})
