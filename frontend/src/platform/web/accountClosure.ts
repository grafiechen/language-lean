import type { AccountScope } from '../../core/reviews'
import { accountKey } from './database'
import { learningDatabase } from './reviewSync'

export const ACCOUNT_CLOSED_EVENT = 'language-lean:account-closed'
export const ACCOUNT_CLOSED_CHANNEL = 'language-lean-account-closed'
/** 只删除注销 UUID 的缓存，其他账号和通用应用壳保留；同事务隔离迟到写入。 */
export async function retireAccount(scope: AccountScope): Promise<void> {
  const db = learningDatabase, key = accountKey(scope)
  // 仅在服务端确认后调用；即使 IndexedDB 暂时不可写，也先阻止旧离线身份恢复。
  try { globalThis.localStorage?.setItem('language-lean.closed-account:' + key, 'true') }
  catch { /* 浏览器禁止 localStorage 时仍尝试原子清理 IndexedDB。 */ }
  await db.transaction('rw', db.tables, async () => {
    const previous = await db.syncMeta.get(key)
    for (const table of db.tables) if (table.name !== 'syncMeta') await table.where('accountKey').equals(key).delete()
    await db.syncMeta.put({ accountKey: key, revision: (previous?.revision ?? 0) + 1, retired: true })
  })
  // 不清空整个 localStorage，避免删除其他账号的选择及仍待同步的记录。
  if (globalThis.localStorage?.getItem('language-lean.active-account') === key) {
    localStorage.removeItem('language-lean.active-account'); localStorage.setItem('language-lean.explicit-logout', 'true')
  }
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(ACCOUNT_CLOSED_EVENT, { detail: scope }))
  if (typeof window !== 'undefined' && 'BroadcastChannel' in window) {
    const channel = new BroadcastChannel(ACCOUNT_CLOSED_CHANNEL); channel.postMessage(scope); channel.close()
  }
}
