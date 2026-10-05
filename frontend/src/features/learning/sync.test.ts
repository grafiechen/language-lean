import { expect, it, vi } from 'vitest'
import { ReviewUploader, type ReviewSyncPorts } from './sync'
import type { CompletedReview } from '../../core/reviews'
import type { ReviewReceipt } from './types'

const scope = { serverId: 'server', userId: 'A' }
/** 模拟离线完成事件；测试关注顺序、重试和账号归属而不依赖真实网络。 */
function event(id: string, time: string): CompletedReview {
  return { eventId: id, attemptId: id, learningItemId: id, progressEpoch: 'epoch',
    baseVersion: '0', submissionVersion: time, completedAt: time, results: [] }
}
/** 模拟服务器的已确认结果。 */
function receipt(review: CompletedReview): ReviewReceipt {
  return { status: 'APPLIED', eventId: review.eventId, learningItemId: review.learningItemId,
    progressEpoch: review.progressEpoch, progressVersion: review.completedAt, lastReviewEventId: review.eventId,
    fsrsAlgorithmVersion: 'test', fsrsState: '{}', lastReviewedAt: review.completedAt,
    nextReviewAt: review.completedAt, reviewCount: 1, lapseCount: 0, automaticEarFocus: false }
}

it('uploads in answer order and shares one flight between automatic and manual upload', async () => {
  let pending = [event('new', '2026-10-01T00:02:00Z'), event('old', '2026-10-01T00:01:00Z')]
  const submit = vi.fn(async (review: CompletedReview) => receipt(review))
  const uploader = new ReviewUploader({ currentScope: async () => scope, pending: async () => [...pending],
    submit, acknowledge: async (_scope, result) => { pending = pending.filter(e => e.eventId !== result.eventId) } })
  const first = uploader.upload(scope)
  expect(uploader.upload(scope)).toBe(first)
  expect(await first).toEqual({ uploaded: 2, failed: 0, remaining: 0, stopped: false })
  expect(submit.mock.calls.map(call => call[0].eventId)).toEqual(['old', 'new'])
})

it('preserves unacknowledged records after a network failure or an account switch', async () => {
  const pending = [event('old', '2026-10-01T00:01:00Z')]
  const acknowledge = vi.fn()
  const ports: ReviewSyncPorts = { currentScope: async () => scope, pending: async () => pending,
    submit: async () => { throw new TypeError('offline') }, acknowledge }
  expect(await new ReviewUploader(ports).upload(scope)).toEqual({ uploaded: 0, failed: 1, remaining: 1, stopped: true })
  ports.currentScope = async () => ({ ...scope, userId: 'B' })
  const submit = vi.fn(async () => receipt(pending[0]))
  ports.submit = submit
  expect(await new ReviewUploader(ports).upload(scope)).toEqual({ uploaded: 0, failed: 0, remaining: 1, stopped: true })
  expect(submit).not.toHaveBeenCalled()
  expect(acknowledge).not.toHaveBeenCalled()
})

it('keeps conflicts cached while uploading independent valid words', async () => {
  let pending = [event('bad', '2026-10-01T00:01:00Z'), event('good', '2026-10-01T00:02:00Z')]
  const uploader = new ReviewUploader({ currentScope: async () => scope, pending: async () => pending,
    submit: async review => { if (review.eventId === 'bad') throw { status: 409 }; return receipt(review) },
    acknowledge: async (_scope, result) => { pending = pending.filter(e => e.eventId !== result.eventId) } })
  expect(await uploader.upload(scope)).toEqual({ uploaded: 1, failed: 1, remaining: 1, stopped: false })
  expect(pending[0].eventId).toBe('bad')
})

it('also drains words completed while an earlier request is in flight', async () => {
  let pending = [event('first', '2026-10-01T00:01:00Z')]
  const submit = vi.fn(async (review: CompletedReview) => {
    if (review.eventId === 'first') pending.push(event('second', '2026-10-01T00:02:00Z'))
    return receipt(review)
  })
  const uploader = new ReviewUploader({ currentScope: async () => scope, pending: async () => [...pending], submit,
    acknowledge: async (_scope, result) => { pending = pending.filter(e => e.eventId !== result.eventId) } })
  expect(await uploader.upload(scope)).toEqual({ uploaded: 2, failed: 0, remaining: 0, stopped: false })
  expect(submit).toHaveBeenCalledTimes(2)
})

it('reconciles before uploading and never sends events revoked by a remote reset', async () => {
  let pending = [event('obsolete', '2026-10-01T00:01:00Z'), event('valid', '2026-10-01T00:02:00Z')]
  const submit = vi.fn(async (review: CompletedReview) => receipt(review))
  const uploader = new ReviewUploader({ currentScope: async () => scope, pending: async () => [...pending], submit,
    reconcile: async () => { pending = pending.filter(event => event.eventId !== 'obsolete'); return { removed: 1 } },
    acknowledge: async (_scope, result) => { pending = pending.filter(event => event.eventId !== result.eventId) } })
  expect(await uploader.upload(scope)).toEqual({ uploaded: 1, failed: 0, remaining: 0, stopped: false, removed: 1 })
  expect(submit.mock.calls.map(call => call[0].eventId)).toEqual(['valid'])
})

it('a failed preflight or wrong account preserves events and prevents both cleanup and submission', async () => {
  const pending = [event('cached', '2026-10-01T00:01:00Z')], submit = vi.fn(), reconcile = vi.fn().mockRejectedValue(new TypeError('offline'))
  const ports: ReviewSyncPorts = { currentScope: async () => scope, pending: async () => pending, submit, reconcile, acknowledge: vi.fn() }
  expect((await new ReviewUploader(ports).upload(scope)).stopped).toBe(true)
  expect(submit).not.toHaveBeenCalled()
  reconcile.mockClear(); ports.currentScope = async () => ({ ...scope, userId: 'B' })
  await new ReviewUploader(ports).upload(scope)
  expect(reconcile).not.toHaveBeenCalled(); expect(submit).not.toHaveBeenCalled()
})
