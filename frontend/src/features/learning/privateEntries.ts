import type { DictionaryContent } from '../dictionary/types'
/** 私有词条完整编辑视图，不含公开状态或发布权限。 */
export interface PrivateEntry {
  id: string; languageCode: string; scriptCode: string; written: string; content: DictionaryContent
  version: number; createdAt: string; updatedAt: string
}
