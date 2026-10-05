import 'fake-indexeddb/auto'
import { afterAll, beforeEach, expect, it, vi } from 'vitest'
import { postJson } from '../../shared/api'
import { audioRequestKey, cacheReadyAudio, ensureAudio, pronunciationHash } from './audio'
import { learningDatabase } from './reviewSync'
import { accountKey } from './database'
import type { AudioResult } from '../../features/audio/types'
vi.mock('../../shared/api', () => ({ postJson: vi.fn() }))
vi.stubGlobal('navigator', { onLine: true })
const owner = { serverId: 'server', userId: 'A' }, other = { serverId: 'server', userId: 'B' }
const request = { entryId: 'private', resourceId: 'reading', kind: 'WORD' as const, scope: 'PERSONAL' as const, pronunciationText: 'ねこ' }
const ready: AudioResult = { status: 'READY', audioVersionId: 'version', textHash: 'hash', url: '/audio', stale: false, message: '' }
beforeEach(async () => { vi.clearAllMocks(); vi.mocked(postJson).mockResolvedValue(ready); await learningDatabase.audio.clear() })
afterAll(async () => { vi.unstubAllGlobals(); await learningDatabase.delete() })
it('routes ordinary owners private regeneration through the personal endpoint and public regeneration through admin', async () => {
  await ensureAudio(owner, request, true)
  expect(postJson).toHaveBeenLastCalledWith('/api/v1/audio/entries/private/resources/reading/regenerate?kind=WORD&scope=PERSONAL')
  await ensureAudio(owner, { ...request, scope: 'OVERRIDE' }, true)
  expect(postJson).toHaveBeenLastCalledWith('/api/v1/audio/entries/private/resources/reading/regenerate?kind=WORD&scope=OVERRIDE')
  await ensureAudio(owner, { ...request, scope: 'PUBLISHED' }, true)
  expect(postJson).toHaveBeenLastCalledWith('/api/v1/admin/audio/entries/private/resources/reading/regenerate?kind=WORD&scope=PUBLISHED')
})
it('shares a concurrent task only within the same account and scope', async () => {
  const finishes: ((value: AudioResult) => void)[] = []
  vi.mocked(postJson).mockImplementation(() => new Promise(resolve => { finishes.push(resolve as (value: AudioResult) => void) }))
  const one = ensureAudio(owner, request), same = ensureAudio(owner, request)
  expect(one).toBe(same)
  const otherTask = ensureAudio(other, request); expect(otherTask).not.toBe(one); expect(postJson).toHaveBeenCalledTimes(2)
  finishes.forEach(finish => finish(ready)); await Promise.all([one, otherTask])
})

it('reuses unchanged override bytes while advancing their structural revision after an example edit', async () => {
  const value = { ...request, scope: 'OVERRIDE' as const, audioRevision: 2 }, hash = await pronunciationHash(value.pronunciationText)
  await learningDatabase.audio.add({ accountKey: accountKey(owner), audioVersionId: 'version', requestKey: audioRequestKey(value), audioRevision: 1,
    cachedAt: '', textHash: hash, blob: new Blob(['same audio']) })
  const blob = await cacheReadyAudio(owner, value, { ...ready, textHash: hash })
  expect(await blob.text()).toBe('same audio')
  expect((await learningDatabase.audio.get([accountKey(owner), 'version']))?.audioRevision).toBe(2)
})
