import type { AccountScope } from '../../core/reviews'
import type { CachedAccountRow } from './database'
import { accountKey, requireLiveCache, confirmedAccountClosed } from './database'
import { learningDatabase } from './reviewSync'

const ACTIVE_KEY = 'language-lean.active-account', BLOCKED_KEY = 'language-lean.explicit-logout'
/** 成功认证后仅记住离线显示身份，不把服务器权限保存在离线账户中。 */
export async function rememberAccount(scope: AccountScope, username: string, nativeLanguage = 'zh-Hans'): Promise<void> {
  const key = accountKey(scope)
  await learningDatabase.transaction('rw', learningDatabase.accounts, learningDatabase.syncMeta, async () => {
    await requireLiveCache(learningDatabase, scope)
    await learningDatabase.accounts.put({ accountKey: key, ...scope, username, nativeLanguage, lastUsedAt: new Date().toISOString() })
  })
  localStorage.setItem(ACTIVE_KEY, key); localStorage.removeItem(BLOCKED_KEY)
}
/** 只展示当前服务器上已有离线资源或草稿的账户，保留其他账户的缓存。 */
export async function availableCachedAccounts(serverId: string): Promise<CachedAccountRow[]> {
  const rows = await learningDatabase.accounts.where('serverId').equals(serverId).toArray()
  const available: CachedAccountRow[] = []
  for (const row of rows) {
    if (confirmedAccountClosed(row.accountKey) || (await learningDatabase.syncMeta.get(row.accountKey))?.retired) continue
    if (await learningDatabase.preparations.where('accountKey').equals(row.accountKey).count()
      || await learningDatabase.resources.where('accountKey').equals(row.accountKey).count()
      || await learningDatabase.pending.where('accountKey').equals(row.accountKey).count()
      || await learningDatabase.drafts.where('accountKey').equals(row.accountKey).count()) available.push(row)
  }
  return available.sort((a, b) => b.lastUsedAt.localeCompare(a.lastUsedAt))
}
/** 恢复最近归属；显式退出后不自动选择账号，也不自动恢复残留服务端会话。 */
export async function lastCachedAccount(serverId: string): Promise<CachedAccountRow | null> {
  if (sessionRestoreBlocked()) return null
  const key = localStorage.getItem(ACTIVE_KEY)
  return (await availableCachedAccounts(serverId)).find(row => row.accountKey === key) ?? null
}
/** 离线选择只改变本机归属，服务器登录和上传仍需单独验证。 */
export function selectCachedAccountKey(key: string): void { localStorage.setItem(ACTIVE_KEY, key) }
/** 显式退出保留学习数据，但阻止页面刷新重新恢复同一会话。 */
export function blockSessionRestore(): void { localStorage.removeItem(ACTIVE_KEY); localStorage.setItem(BLOCKED_KEY, 'true') }
/** 退出标记只由成功在线登录清除。 */
export function sessionRestoreBlocked(): boolean { return localStorage.getItem(BLOCKED_KEY) === 'true' }
