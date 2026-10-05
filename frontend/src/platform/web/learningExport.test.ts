import 'fake-indexeddb/auto'
import { beforeEach, afterAll, expect, it, vi } from 'vitest'
import { localLearningExport, learningExport } from './learningExport'
import { learningDatabase } from './reviewSync'
import { accountKey } from './database'
import { getJson } from '../../shared/api'
vi.mock('../../shared/api', async original => ({ ...await original<typeof import('../../shared/api')>(), getJson: vi.fn() }))
vi.stubGlobal('window', { location: { origin: 'https://learn.test' } })
const owner = { serverId: 'https://learn.test', userId: 'A' }, other = { ...owner, userId: 'B' }
beforeEach(async () => { vi.clearAllMocks(); await Promise.all(learningDatabase.tables.map(table => table.clear())) })
afterAll(async () => { vi.unstubAllGlobals(); await learningDatabase.delete() })
it('exports only the selected offline partition, omitting credentials, display identity and binary audio', async () => {
  await learningDatabase.accounts.put({ accountKey: accountKey(owner), ...owner, username: 'Private Name', nativeLanguage: 'en', lastUsedAt: '' })
  await learningDatabase.issues.bulkPut([owner,other].map(scope => ({ accountKey: accountKey(scope), eventId: scope.userId, learningItemId: 'item', progressEpoch: 'epoch', code: 'TEST', message: 'retry', status: 409, detectedAt: '' })))
  await learningDatabase.audio.put({ accountKey: accountKey(owner), audioVersionId: 'audio', requestKey: 'r', textHash: 'hash', cachedAt: '', blob: new Blob(['private audio']) })
  const value = await localLearningExport(owner), serialized = JSON.stringify(value)
  expect(value.nativeLanguage).toBe('en'); expect(value.tables.issues).toHaveLength(1)
  expect(serialized).not.toContain('Private Name'); expect(serialized).not.toContain('private audio'); expect(serialized).not.toContain('"blob"')
  expect((await learningExport(owner,false)).includesServerData).toBe(false); expect(getJson).not.toHaveBeenCalled()
})
it('rejects another server session or a changed owner instead of packaging mixed data', async () => {
  vi.mocked(getJson).mockResolvedValueOnce({ id: 'B' })
  await expect(learningExport(owner,true)).rejects.toThrow('登录账号已变化')
  vi.mocked(getJson).mockResolvedValueOnce({ id: 'A' }).mockResolvedValueOnce({ userId: 'B' })
  await expect(learningExport(owner,true)).rejects.toThrow('备份账号')
  vi.mocked(getJson).mockResolvedValueOnce({ id: 'A' }).mockResolvedValueOnce({ userId: 'A' }).mockResolvedValueOnce({ id: 'B' })
  await expect(learningExport(owner,true)).rejects.toThrow('登录账号已变化')
})
