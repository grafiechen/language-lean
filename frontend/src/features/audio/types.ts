/** 每种音频状态都有明确提示；未就绪音频不能进入听音评分。 */
export interface AudioResult {
  status: 'READY' | 'PENDING' | 'MISSING_PRONUNCIATION' | 'DISABLED' | 'NOT_CONFIGURED' | 'FAILED'
  audioVersionId: string | null
  url: string | null
  stale: boolean
  message: string
  textHash: string | null
}
/** 一个有稳定身份和显式发音输入的资源。 */
export type AudioScope = 'PUBLISHED' | 'DRAFT' | 'PERSONAL' | 'OVERRIDE'
export interface AudioRequest { entryId: string; resourceId: string; kind: 'WORD' | 'EXAMPLE'; scope: AudioScope; pronunciationText: string; audioRevision?: number }
/** 后台语言声音设置，只包含非敏感字段。 */
export interface TtsSetting {
  languageCode: string; displayName: string; locale: string; provider: string; model: string; voice: string
  enabled: boolean; personalAutoGenerate: boolean; version: number | null
}
