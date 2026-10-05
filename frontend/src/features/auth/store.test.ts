import 'fake-indexeddb/auto'
import { beforeEach, afterAll, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError, getJson } from '../../shared/api'
import { useAuth } from './store'
import { learningDatabase } from '../../platform/web/reviewSync'
import { rememberAccount } from '../../platform/web/cachedAccounts'
import { accountKey } from '../../platform/web/database'

vi.mock('../../shared/api', async original => ({ ...await original<typeof import('../../shared/api')>(),
  getJson: vi.fn(), postJson: vi.fn(), postForm: vi.fn() }))
const origin = 'https://learn.test', owner = { serverId: origin, userId: 'A' }
const storage = new Map<string, string>()
vi.stubGlobal('window', { location: { origin } })
vi.stubGlobal('localStorage', { getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => storage.set(key, value), removeItem: (key: string) => storage.delete(key) })
beforeEach(async () => {
  setActivePinia(createPinia()); vi.clearAllMocks(); storage.clear()
  await Promise.all([learningDatabase.accounts.clear(), learningDatabase.preparations.clear(), learningDatabase.resources.clear()])
})
afterAll(() => { vi.unstubAllGlobals(); return learningDatabase.delete() })
/** 账户必须有实际草稿或准备范围才能作为离线归属恢复。 */
async function cacheOwner() {
  await rememberAccount(owner, '学习者 A')
  await learningDatabase.resources.put({ accountKey: accountKey(owner), batchId: 'draft', wordbookId: 'book', createdAt: '', items: {} })
}
it('does not create an offline identity on first visit without a prepared account', async () => {
  vi.mocked(getJson).mockRejectedValue(new ApiError(401, 'unauthorized'))
  const auth = useAuth(); expect(await auth.restore()).toBe(false); expect(auth.user).toBeNull()
})
it('stores only display identity after online authentication, without passwords or cached permissions', async () => {
  vi.mocked(getJson).mockResolvedValue({ id: 'A', username: '管理员', email: 'private@test', roles: ['ADMIN'], mustChangePassword: true })
  const auth = useAuth(); expect(await auth.restore()).toBe(true); expect(auth.serverAuthenticated).toBe(true)
  const row = (await learningDatabase.accounts.toArray())[0]
  expect(Object.keys(row).sort()).toEqual(['accountKey', 'lastUsedAt', 'nativeLanguage', 'serverId', 'userId', 'username'])
})
it('keeps native language with the account across offline restore and switching', async () => {
  await rememberAccount(owner, '学习者 A', 'en')
  await learningDatabase.resources.put({ accountKey: accountKey(owner), batchId: 'draft', wordbookId: 'book', createdAt: '', items: {} })
  vi.mocked(getJson).mockRejectedValue(new TypeError('offline'))
  const auth = useAuth(); expect(await auth.restore()).toBe(true); expect(auth.user?.nativeLanguage).toBe('en')
  expect(await auth.saveNativeLanguage('ja')).toBe(false)
  expect(auth.user?.nativeLanguage).toBe('en')
})
it('expired login and network failures restore the prepared account without administrator permissions', async () => {
  await cacheOwner()
  for (const failure of [new ApiError(401, 'expired'), new TypeError('network down')]) {
    vi.mocked(getJson).mockRejectedValue(failure)
    const auth = useAuth(); expect(await auth.restore()).toBe(true)
    expect(auth.mode).toBe('CACHED'); expect(auth.serverAuthenticated).toBe(false)
    expect(auth.user?.id).toBe('A'); expect(auth.user?.roles).toEqual([])
  }
})
it('explicit logout blocks automatic session revival but still permits an explicit local account choice', async () => {
  await cacheOwner(); localStorage.setItem('language-lean.explicit-logout', 'true')
  const auth = useAuth(); expect(await auth.restore()).toBe(false); expect(getJson).not.toHaveBeenCalled()
  expect(await auth.selectCached(accountKey(owner))).toBe(true); expect(auth.mode).toBe('CACHED')
})
it('selecting a cached account does not query the currently logged-in server account or expose another origin', async () => {
  await cacheOwner(); await rememberAccount({ serverId: 'https://other.test', userId: 'B' }, 'B')
  const auth = useAuth(); expect(await auth.selectCached(accountKey(owner))).toBe(true)
  expect(await auth.selectCached(accountKey({ serverId: 'https://other.test', userId: 'B' }))).toBe(false)
  expect(auth.user?.id).toBe('A'); expect(getJson).not.toHaveBeenCalled()
})
it('explicit server confirmation of deletion never falls back to the old cached identity, even before local purge succeeds', async () => {
  await cacheOwner(); vi.mocked(getJson).mockRejectedValue(new ApiError(401, 'ACCOUNT_DELETED', '账号已注销；本机清理失败'))
  const auth = useAuth(); expect(await auth.restore()).toBe(false)
  expect(auth.user).toBeNull(); expect(auth.mode).toBe('NONE'); expect(auth.error).toContain('本机清理失败')
})
it('a closure notification clears only the matching in-memory account', async () => {
  await cacheOwner(); const auth = useAuth(); await auth.selectCached(accountKey(owner))
  expect(auth.accountClosed('other-uuid')).toBe(false); expect(auth.user?.id).toBe('A')
  expect(auth.accountClosed('A')).toBe(true); expect(auth.user).toBeNull(); expect(auth.mode).toBe('NONE')
})
