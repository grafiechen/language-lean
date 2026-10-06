import { expect, it } from 'vitest'
import { backupFailure, backupStatus, type BackupJob } from './backups'
const base: BackupJob = { id: 'test', triggerKind: 'MANUAL', state: 'SUCCESS', createdAt: '', startedAt: null, finishedAt: null,
  errorCode: null, encryptedBytes: 100, encryptionKeyId: 'test-key', archiveState: 'VERIFIED' }
it('does not call a cleaned archive usable or confuse an unconfirmed result with success', () => {
  expect(backupStatus(base)).toBe('已上传并验证')
  expect(backupStatus({ ...base, archiveState: 'DELETED' })).toBe('归档已清理')
  expect(backupStatus({ ...base, state: 'FAILED', archiveState: 'DELETE_PENDING' })).toBe('等待清理失败归档')
  expect(backupStatus({ ...base, archiveState: 'DELETE_PENDING' })).toBe('等待清理过期归档')
  expect(backupFailure('RESULT_UNCONFIRMED')).toContain('未确认')
  expect(backupFailure('secret-host-error')).not.toContain('secret-host-error')
})
