/** 后台仅接收汇总及配置布尔值，禁止加入个人正文或凭据。 */
export interface SystemUsage {
  generatedAt: string; month: string; timezone: 'UTC'; firstRecordedRequest: string | null
  accounts: { active: number; disabled: number }
  dictionary: { published: number; draft: number; banned: number }
  audio: { resources: number; resourcesWithVersion: number; versions: number; generating: number; failed: number; pendingCleanup: number }
  generation: { requests: number; inputCharacters: number; returned: number; responseBytes: number; ready: number; failed: number; discarded: number; unconfirmed: number }
  work: { pendingContributions: number; pendingAudioFeedback: number }
  configuration: { googleTtsEnabled: boolean; r2Configured: boolean; mailConfigured: boolean; passwordRecoveryConfigured: boolean; secureSessionCookie: boolean; persistentPasswordMasterConfigured: boolean }
}
/** 使用服务端UTC自然月，避免浏览器所在时区跨月而选错统计范围。 */
export function utcMonth(now = new Date()): string { return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}` }
