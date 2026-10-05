import type { AccountScope } from '../../core/reviews'
import { accountKey } from './database'
import { learningDatabase, currentReviewScope } from './reviewSync'
import { getJson } from '../../shared/api'

/** 在一致事务内导出本机草稿和待上传事件，不含密码、会话及音频二进制。 */
export async function localLearningExport(scope: AccountScope) {
  const db = learningDatabase, key = accountKey(scope)
  return db.transaction('r', db.tables, async () => {
    const rows: Record<string, unknown> = {}
    for (const table of db.tables.filter(table => table.name !== 'accounts' && table.name !== 'audio'))
      rows[table.name] = await table.where('accountKey').equals(key).toArray()
    rows.audioVersions = (await db.audio.where('accountKey').equals(key).toArray()).map(({ blob, ...metadata }) => metadata)
    const account = await db.accounts.get(key)
    return { schemaVersion: 1, scope: 'LOCAL', account: scope, nativeLanguage: account?.nativeLanguage ?? 'zh-Hans',
      exportedAt: new Date().toISOString(), tables: rows }
  })
}
/** 联网时同时读取服务器完整数据；下载前后核对账号，避免切换账号混入备份。 */
export async function learningExport(scope: AccountScope, online: boolean) {
  const assertOwner = async () => {
    const current = await currentReviewScope()
    if (current.serverId !== scope.serverId || current.userId !== scope.userId) throw new Error('登录账号已变化，请重新选择备份账号。')
  }
  if (online) await assertOwner()
  const server = online ? await getJson<{ userId: string }>('/api/v1/learning/export') : null
  if (server && server.userId !== scope.userId) throw new Error('备份账号与当前学习归属不一致。')
  const local = await localLearningExport(scope)
  if (online) await assertOwner()
  return { schemaVersion: 1, application: 'language-lean', exportedAt: new Date().toISOString(),
    account: scope, server, local, includesServerData: !!server, includesAudioFiles: false }
}
/** 下载JSON后释放对象地址；不触发进度上传或恢复。 */
export function downloadLearningExport(value: unknown) {
  const blob = new Blob([JSON.stringify(value, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob), anchor = document.createElement('a')
  anchor.href = url; anchor.download = 'language-lean-backup-' + new Date().toISOString().replace(/[:.]/g, '-') + '.json'
  anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
