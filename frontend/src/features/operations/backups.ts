/** 服务器灾备仅返回脱敏任务元信息，不包含下载链接、对象键、连接或密钥。 */
export interface DatabaseBackups {
  enabled: boolean; ready: boolean; encryptionConfigured: boolean; databaseDumpConfigured: boolean; storageConfigured: boolean; policyValid: boolean
  intervalHours: number; retentionDays: number; jobs: BackupJob[]
}
export interface BackupJob {
  id: string; triggerKind: 'MANUAL' | 'SCHEDULED'; state: 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  createdAt: string; startedAt: string | null; finishedAt: string | null; errorCode: string | null
  encryptedBytes: number | null; encryptionKeyId: string | null; archiveState: string | null
}
export function backupStatus(job: BackupJob): string {
  if (job.archiveState === 'DELETED') return '归档已清理'
  if (job.archiveState === 'DELETE_PENDING') return job.state === 'FAILED' ? '等待清理失败归档' : '等待清理过期归档'
  return { QUEUED: '等待执行', RUNNING: '正在备份', SUCCESS: '已上传并验证', FAILED: '执行失败' }[job.state]
}
export function backupFailure(code: string | null): string {
  const messages: Record<string, string> = { DUMP_FAILED: '数据库转储失败，请检查连接、权限及备份镜像。',
    UPLOAD_OR_VERIFICATION_FAILED: '上传或回读核验失败，请检查私有备份存储。', RESULT_UNCONFIRMED: '执行结果未确认，请核对服务器运行状态。',
    WORKER_INTERRUPTED: '任务中断或认领过期，可以重新发起备份。' }
  return code ? messages[code] ?? '执行失败，请核对服务器运行状态。' : ''
}
