import type { Translation } from './types'
/** 母语选项用于译文展示，和后台启用的学习语言及TTS配置分开。 */
export const nativeLanguages = [
  { code: 'zh-Hans', name: '简体中文' }, { code: 'zh-Hant', name: '繁體中文' }, { code: 'en', name: 'English' },
  { code: 'ja', name: '日本語' }, { code: 'ko', name: '한국어' }, { code: 'de', name: 'Deutsch' },
  { code: 'fr', name: 'Français' }, { code: 'es', name: 'Español' },
  { code: 'zh', name: '中文（未区分字形）' },
]
export function languageName(code: string): string { return nativeLanguages.find(value => value.code === code)?.name ?? (code || '未标注语言') }
export interface LocalizedText { text: string; language: string; fallback: boolean; attribution?: Translation }
/** 优先精确母语译文。仅在来源明确为JMdict时推断旧快照的英语；不把英文冒充中文。 */
export function localize(text: string, language: string | undefined, translations: Record<string, Translation> | undefined,
  nativeLanguage: string, sourceName: string): LocalizedText {
  const actualLanguage = language || (sourceName.includes('JMdict') ? 'en' : '')
  if (actualLanguage === nativeLanguage && text.trim()) return { text, language: actualLanguage, fallback: false }
  const preferred = translations?.[nativeLanguage]
  if (preferred?.text?.trim()) return { text: preferred.text, language: nativeLanguage, fallback: false, attribution: preferred }
  // 来源只标注通用中文时，可作为简繁母语的译文；不擅自转换字形。
  const parent = nativeLanguage.split('-')[0]!
  const generic = translations?.[parent]
  if (parent !== nativeLanguage && generic?.text?.trim()) return { text: generic.text, language: parent, fallback: false, attribution: generic }
  if (actualLanguage === parent && text.trim()) return { text, language: actualLanguage, fallback: false }
  return { text, language: actualLanguage, fallback: !!text.trim() && actualLanguage !== nativeLanguage }
}
