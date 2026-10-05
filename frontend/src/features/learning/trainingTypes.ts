import type { PublicEntry } from '../dictionary/types'
import type { LearningItem } from './types'
import type { PersonalContent } from './personalContent'
/** 固定训练内容与音频版本，恢复和离线答题不混用新旧资源。 */
export interface TrainingWord {
  learning: LearningItem; entry: PublicEntry; readingId: string; audioVersionId: string; textHash: string
  /** 准备后发生缓存清理时拒绝用已移除的临时下载启动新批次；旧草稿可缺省。 */
  cacheRevision?: number
  /** 私人释义、笔记和标签随账号分区快照缓存；旧批次可缺少。 */
  personal?: PersonalContent
  /** 耳词各读音均绑定明确音频版本，切换读音不拆分进度。旧缓存可仅含主要音频。 */
  readingAudio?: { readingId: string; audioVersionId: string; textHash: string }[]
}
