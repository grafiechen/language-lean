import type { AccountScope } from '../../core/reviews'
import type { LearningContent, PersonalContent } from '../../features/learning/personalContent'
import { trainableStatus } from '../../features/learning/types'
import { accountKey, bumpCacheRevision } from './database'
import { learningDatabase } from './reviewSync'
import { invalidateOfflinePreparations } from './offlineLearning'

/** 读取本人上次准备的有效内容；离线详情不查询公开 API 或其他账号。 */
export async function cachedLearningContent(scope: AccountScope, itemId: string): Promise<LearningContent | null> {
  const db = learningDatabase, key = accountKey(scope)
  return db.transaction('r', db.preparations, db.resources, async () => {
    const rows = await db.preparations.where('accountKey').equals(key).toArray()
    rows.sort((a, b) => b.preparedAt.localeCompare(a.preparedAt))
    for (const row of rows) {
      const content = row.contents?.find(value => value.learningItemId === itemId)
      const item = row.items.find(item => item.id === itemId)
      if (content && item) return { ...content, entry: trainableStatus(item.status) ? content.entry
        : { ...content.entry, status: item.status, content: null } }
    }
    let word = rows.flatMap(row => row.words).find(word => word.learning.id === itemId)
    if (!word) {
      const resources = await db.resources.where('accountKey').equals(key).toArray()
      resources.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      word = resources.flatMap(row => Object.values(row.items)).find(word => word.learning.id === itemId)
    }
    if (!word) return null
    const personal: PersonalContent = word.personal ?? { revision: 0, meaningOverride: null, notes: '', tags: [], updatedAt: null }
    const entry = trainableStatus(word.learning.status) ? word.entry
      : { ...word.entry, status: word.learning.status, content: null }
    return { learningItemId: itemId, entry, personal }
  })
}

/** 私人内容变动只使后续离线准备失效；当前队列、FSRS、事件和其他账号保持原样。 */
export async function invalidatePersonalContentDownloads(scope: AccountScope, itemId: string, revision: number, audioRevision?: number): Promise<void> {
  const db = learningDatabase, key = accountKey(scope)
  await db.transaction('rw', [db.preparations, db.syncMeta, db.resources, db.drafts, db.audio], async () => {
    await bumpCacheRevision(db, scope)
    for (const row of await db.preparations.where('accountKey').equals(key).toArray()) {
      if (row.items.some(item => item.id === itemId) || row.words.some(word => word.learning.id === itemId)) {
        const word = row.words.find(word => word.learning.id === itemId)
        const content = row.contents?.find(value => value.learningItemId === itemId)
        const audioChanged = audioRevision !== undefined && (content?.personal.audioRevision ?? word?.personal?.audioRevision
          ?? row.items.find(item => item.id === itemId)?.personalAudioRevision ?? 0) !== audioRevision
        if (audioChanged) {
          row.words = row.words.filter(word => word.learning.id !== itemId)
          if (row.contents) row.contents = row.contents.filter(content => content.learningItemId !== itemId)
        }
        if (audioChanged || (content?.personal.revision ?? word?.personal?.revision ?? word?.learning.personalContentRevision ?? 0) !== revision) {
          row.status = 'PARTIAL'; row.failures = ['个人内容已修改，请联网重新准备此单词本。']
          await db.preparations.put(row)
        }
      }
    }
    if (audioRevision !== undefined) {
      for (const resource of await db.resources.where('accountKey').equals(key).toArray()) {
        const word = resource.items[itemId]
        if (word && (word.personal?.audioRevision ?? word.learning.personalAudioRevision ?? 0) !== audioRevision) {
          await db.resources.delete([key, resource.batchId]); await db.drafts.delete([key, resource.batchId])
        }
      }
      for (const audio of await db.audio.where('accountKey').equals(key).toArray()) {
        let request: unknown[]; try { request = JSON.parse(audio.requestKey) as unknown[] } catch { continue }
        if (request[1] === 'OVERRIDE' && request[0] === itemId) await db.audio.delete([key, audio.audioVersionId])
      }
    }
  })
}

/** 已确认的私有删除同时清除正文和本机音频，不把私人副本留作未引用缓存。 */
export async function purgeDeletedPrivateCache(scope: AccountScope, learningIds: string[], privateIds: string[] = []): Promise<number> {
  const db = learningDatabase, key = accountKey(scope), deleted = new Set(learningIds), entries = new Set(privateIds)
  return db.transaction('rw', [db.pending, db.states, db.issues, db.projections, db.drafts, db.resources, db.preparations, db.syncMeta, db.audio], async () => {
    const preparations = await db.preparations.where('accountKey').equals(key).toArray()
    const resources = await db.resources.where('accountKey').equals(key).toArray()
    for (const row of preparations) for (const item of row.items)
      if (deleted.has(item.id) && item.personalCustomEntryId) entries.add(item.personalCustomEntryId)
    for (const row of resources) for (const word of Object.values(row.items))
      if (deleted.has(word.learning.id) && word.learning.personalCustomEntryId) entries.add(word.learning.personalCustomEntryId)
    for (const item of [...preparations.flatMap(row => row.items), ...resources.flatMap(row => Object.values(row.items).map(word => word.learning))])
      if (item.personalCustomEntryId && entries.has(item.personalCustomEntryId)) deleted.add(item.id)
    await invalidateOfflinePreparations(scope, Object.fromEntries([...deleted].map(id => [id, null])))
    let changed = 0
    for (const row of await db.preparations.where('accountKey').equals(key).toArray()) {
      if (row.items.some(item => !!item.personalCustomEntryId && entries.has(item.personalCustomEntryId))
          || row.contents?.some(content => content.entry.originType === 'PRIVATE' && entries.has(content.entry.id))) {
        row.items = row.items.filter(item => !item.personalCustomEntryId || !entries.has(item.personalCustomEntryId))
        row.words = row.words.filter(word => word.entry.originType !== 'PRIVATE' || !entries.has(word.entry.id))
        if (row.contents) row.contents = row.contents.filter(content => content.entry.originType !== 'PRIVATE' || !entries.has(content.entry.id))
        row.status = 'PARTIAL'; row.failures = ['私有词条已删除，请重新准备此单词本。']; changed++
        await db.preparations.put(row)
      }
    }
    for (const audio of await db.audio.where('accountKey').equals(key).toArray()) {
      let request: unknown[]; try { request = JSON.parse(audio.requestKey) as unknown[] } catch { continue }
      if (request[1] === 'PERSONAL' && entries.has(String(request[0])) || request[1] === 'OVERRIDE' && deleted.has(String(request[0])))
        await db.audio.delete([key, audio.audioVersionId])
    }
    return changed
  })
}
