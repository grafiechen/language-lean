import type { AccountScope } from '../../core/reviews'
import type { Wordbook } from '../../features/learning/types'
import { accountKey, bumpCacheRevision, requireLiveCache, type LearningDatabase } from './database'
import { learningDatabase } from './reviewSync'

/** 只更新已有本机快照的分类信息，保持词条、音频、准备状态、草稿和待上传事件。 */
export async function cacheWordbookMetadata(scope: AccountScope, book: Wordbook, db: LearningDatabase = learningDatabase) {
  const key = accountKey(scope)
  await db.transaction('rw', db.preparations, db.syncMeta, async () => {
    await requireLiveCache(db, scope)
    const row = await db.preparations.get([key, book.id])
    if (!row || (row.book.version ?? -1) > (book.version ?? -1)) return
    row.book = { ...row.book, name: book.name, description: book.description, updatedAt: book.updatedAt, version: book.version }
    await db.preparations.put(row)
    await bumpCacheRevision(db, scope)
  })
}
