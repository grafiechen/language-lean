<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { deleteJson, getJson, postJson } from '../shared/api'
import type { LearningItem, Wordbook, ReviewHistory } from '../features/learning/types'
import WordbookEditor from '../features/learning/WordbookEditor.vue'
import { learningStatusLabel } from '../features/learning/types'
import { useAuth } from '../features/auth/store'
import { learningDatabase, pendingReviews, reviewUploader } from '../platform/web/reviewSync'
import { accountKey, type SyncIssueRow } from '../platform/web/database'
import { reconcileLearning, reconciliationMessages } from '../platform/web/reconciliation'
import { discardUnsyncedReview } from '../platform/web/syncIssues'
import { IonPage, IonContent, onIonViewWillEnter } from '@ionic/vue'
import { invalidateOfflinePreparations as invalidateLearningProgress, localLearningItem, offlinePreparations, reconcileOfflineMembership, cacheManualEarFocus } from '../platform/web/offlineLearning'
import { networkOnline } from '../platform/web/connectivity'
import { purgeDeletedPrivateCache } from '../platform/web/personalContent'
import { matchesLearningFilter, summarizeLearning, type LearningFilter } from '../features/learning/overview'
import LearningCsvImport from '../features/learning/LearningCsvImport.vue'

const wordbooks = ref<Wordbook[]>([])
const selectedId = ref('')
const items = ref<LearningItem[]>([])
const name = ref('')
const description = ref('')
const busy = ref(false)
const error = ref('')
const message = ref('')
const auth = useAuth()
const route = useRoute(), router = useRouter()
const createDialog = ref<HTMLDialogElement | null>(null)
const wordbookEditor = ref<InstanceType<typeof WordbookEditor> | null>(null)
const bookMenu = ref<HTMLDetailsElement | null>(null)
function openBookEditor() {
  if (!selectedBook.value || !online.value || busy.value) return
  wordbookEditor.value?.open(selectedBook.value)
  if (bookMenu.value) bookMenu.value.open = false
}
/** 保存分类信息只替换对应列表摘要，保留当前学习范围和队列。 */
function wordbookEdited(book: Wordbook, warning: string) {
  loadSequence++; loading.value = false
  wordbooks.value = wordbooks.value.map(value => value.id === book.id ? book : value)
  message.value = warning || '单词本信息已保存。'
}
const filter = ref<LearningFilter>('all')
const bookItems = ref<Record<string, LearningItem[]>>({})
const loading = ref(false)
const resumableBooks = ref<string[]>([])
let loadSequence = 0
const selectedBook = computed(() => wordbooks.value.find(book => book.id === selectedId.value))
const showingBook = computed(() => !!selectedBook.value && route.query.book === selectedId.value)
const visibleItems = computed(() => items.value.filter(item => matchesLearningFilter(item, filter.value)))
const summaries = computed(() => Object.fromEntries(wordbooks.value.map(book => [book.id,
  bookItems.value[book.id] ? summarizeLearning(bookItems.value[book.id]) : null])))
const overall = computed(() => wordbooks.value.every(book => !!bookItems.value[book.id])
  ? summarizeLearning(Object.values(bookItems.value).flat()) : null)
const pendingCount = ref(0)
const uploading = ref(false)
const checking = ref(false), issues = ref<SyncIssueRow[]>([])
const historyItem = ref<LearningItem | null>(null)
const history = ref<ReviewHistory[]>([])
const historyLoading = ref(false)
const scope = computed(() => auth.user ? { serverId: window.location.origin, userId: auth.user.id } : null)
const online = computed(() => auth.serverAuthenticated && networkOnline.value)
const syncNotice = computed(() => scope.value ? reconciliationMessages.value[accountKey(scope.value)] : '')

/** 未上传数量来自当前账号的持久化事件，切换账号不能混用缓存。 */
async function loadPendingCount() {
  const owner = scope.value ? { ...scope.value } : null
  try {
    const key = owner ? accountKey(owner) : ''
    const [events, conflictRows, resources, drafts] = owner ? await Promise.all([pendingReviews.list(owner),
      learningDatabase.issues.where('accountKey').equals(key).toArray(), learningDatabase.resources.where('accountKey').equals(key).toArray(),
      learningDatabase.drafts.where('accountKey').equals(key).toArray()]) : [[], [], [], []]
    if (scope.value?.userId !== owner?.userId) return
    pendingCount.value = events.length; issues.value = conflictRows
    const active = new Set(drafts.filter(row => row.payload.currentGroupIndex < row.payload.groups.length).map(row => row.batchId))
    resumableBooks.value = [...new Set(resources.filter(row => active.has(row.batchId)).map(row => row.wordbookId))]
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '本地进度读取失败' }
}

/** 手动补传所有积压进度，包含网络故障遗留的较早记录；仅服务器确认后才删除缓存。 */
async function uploadProgress() {
  if (!scope.value || uploading.value) return
  const owner = { ...scope.value }
  uploading.value = true; error.value = ''; message.value = ''
  try {
    const result = await reviewUploader.upload(owner)
    if (scope.value?.userId !== owner.userId) return
    message.value = `已上传 ${result.uploaded} 条复习进度，仍有 ${result.remaining} 条待上传。`
    if (result.stopped) message.value += '请确认网络和登录账号后重试。'
    else if (result.failed) message.value += '部分记录需要处理版本或学习身份冲突，已保留本地缓存。'
    await loadPendingCount(); await loadWordbooks()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '进度上传失败，记录仍保留在本地' }
  finally { uploading.value = false }
}

/** 主动核对其他设备的删除/重置，不必等到旧记录上传失败。 */
async function checkRemote() {
  if (!scope.value || !online.value || checking.value) return
  checking.value = true; error.value = ''
  try { await reconcileLearning({ ...scope.value }); await loadWordbooks(); await loadPendingCount() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '核对失败，本地进度仍保留。' }
  finally { checking.value = false }
}
/** 丢弃不可提交记录需明确确认，依赖链一并撤销，不能伪装成上传成功。 */
async function discardIssue(issue: SyncIssueRow) {
  if (!scope.value || !window.confirm('确定丢弃这条未上传记录及依赖它的后续未上传记录吗？相关未完成训练也会取消，服务器已确认的进度不变。')) return
  try { const count = await discardUnsyncedReview({ ...scope.value }, issue.eventId); message.value = `已丢弃 ${count} 条未上传记录。`; await loadPendingCount(); await loadWordbooks() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '记录清理失败。' }
}

/** 展示一个共享学习条目的最近完成记录，不把未完成尝试计入复习次数。 */
async function showHistory(item: LearningItem) {
  historyItem.value = item; history.value = []; historyLoading.value = true; error.value = ''
  try {
    const rows = await getJson<ReviewHistory[]>('/api/v1/learning/items/' + item.id + '/reviews')
    if (historyItem.value?.id === item.id) history.value = rows
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '复习历史加载失败' }
  finally { if (historyItem.value?.id === item.id) historyLoading.value = false }
}
/** 手动重点跨本共享，取消标记不改变自动耳词及已有复习进度。 */
async function toggleManualFocus(item: LearningItem) {
  if (!online.value || busy.value) return
  const owner = scope.value ? { ...scope.value } : null; busy.value = true; error.value = ''
  try {
    const updated = await postJson<LearningItem>('/api/v1/learning/items/' + item.id + '/ear-focus', { enabled: !item.manualEarFocus })
    if (owner && scope.value?.userId === owner.userId) { await cacheManualEarFocus(owner, updated); await loadWordbooks() }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重点状态更新失败' }
  finally { busy.value = false }
}

/** 导入后重新读取共享进度；新增词仍需在离线准备页单独下载。 */
async function csvImported() {
  const bookId = selectedId.value, owner = scope.value ? { ...scope.value } : null
  await loadWordbooks()
  if (owner && scope.value?.userId === owner.userId) await reconcileOfflineMembership(owner, bookId, bookItems.value[bookId] ?? null)
}

/** 时间在浏览器中按当前时区显示，数据库仍保存实际 UTC 答题时间。 */
function displayTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '尚未复习'
}

/** 汇总当前账号的所有单词本；失败的单本不显示虚假的零统计，迟到请求不覆盖新加载。 */
async function loadWordbooks() {
  const sequence = ++loadSequence, owner = scope.value ? { ...scope.value } : null
  const connected = online.value
  loading.value = true
  try {
    const cached = !connected && owner ? await offlinePreparations(owner) : []
    const books = connected ? await getJson<Wordbook[]>('/api/v1/learning/wordbooks') : cached.map(row => row.book)
    const results = await Promise.allSettled(books.map(async book => {
      const rows = connected ? await getJson<LearningItem[]>('/api/v1/learning/wordbooks/' + book.id + '/items')
        : cached.find(row => row.wordbookId === book.id)?.items ?? []
      return owner ? Promise.all(rows.map(item => localLearningItem(owner, item))) : rows
    }))
    if (sequence !== loadSequence || scope.value?.userId !== owner?.userId) return
    wordbooks.value = books
    bookItems.value = Object.fromEntries(results.flatMap((result, index) => result.status === 'fulfilled' ? [[books[index].id, result.value]] : []))
    if (results.some(result => result.status === 'rejected')) error.value = '部分单词本加载失败，请重新加载后查看完整统计。'
    selectedId.value = books.some(book => book.id === route.query.book) ? String(route.query.book) : ''
    items.value = bookItems.value[selectedId.value] ?? []
  } catch (cause) { if (sequence === loadSequence) error.value = cause instanceof Error ? cause.message : '单词本加载失败' }
  finally { if (sequence === loadSequence) loading.value = false }
}
/** 读取选中单词本的学习条目。 */
async function loadItems(bookId = selectedId.value): Promise<LearningItem[] | undefined> {
  if (!bookId) { items.value = []; return }
  const owner = scope.value ? { ...scope.value } : null
  try {
    const rows = online.value ? await getJson<LearningItem[]>('/api/v1/learning/wordbooks/' + bookId + '/items')
      : owner ? (await offlinePreparations(owner)).find(row => row.wordbookId === bookId)?.items ?? [] : []
    const merged = owner ? await Promise.all(rows.map(item => localLearningItem(owner, item))) : rows
    if (scope.value?.userId !== owner?.userId) return
    bookItems.value[bookId] = merged
    if (selectedId.value === bookId) items.value = merged
    return merged
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '学习条目加载失败' }
}
/** 创建单词本后立即选中它，方便从词典页继续加入词条。 */
async function createWordbook() {
  if (!online.value || busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const created = await postJson<Wordbook>('/api/v1/learning/wordbooks', { name: name.value, description: description.value })
    name.value = ''; description.value = ''; selectedId.value = created.id
    message.value = '单词本已创建。'
    createDialog.value?.close()
    await router.push({ path: '/learning', query: { book: created.id } })
    await loadWordbooks()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '单词本创建失败' }
  finally { busy.value = false }
}
/** 删除当前单词本，并在确认后清理其中已无其他关联的学习进度。 */
async function removeWordbook(book: Wordbook) {
  if (!online.value) return
  if (!window.confirm(`确定删除“${book.name}”吗？其中只属于此单词本的学习进度也会删除。`)) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const owner = scope.value
    const deleted = await deleteJson<{ deletedLearningItemIds: string[] }>('/api/v1/learning/wordbooks/' + book.id)
    if (owner) await purgeDeletedPrivateCache(owner, deleted.deletedLearningItemIds, items.value.filter(item => deleted.deletedLearningItemIds.includes(item.id) && item.personalCustomEntryId).map(item => item.personalCustomEntryId!))
    if (owner) await reconcileOfflineMembership(owner, book.id, null)
    selectedId.value = ''; await loadWordbooks(); await loadPendingCount(); message.value = '单词本已删除。'
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '单词本删除失败' }
  finally { busy.value = false }
}
/** 完整重置单词本范围，并清除共享学习条目的进度和手动重点。 */
async function resetWordbook() {
  if (!online.value) return
  const book = wordbooks.value.find(value => value.id === selectedId.value)
  if (!book || !window.confirm(`确定重置“${book.name}”内所有词条的学习进度吗？`)) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const owner = scope.value
    await postJson('/api/v1/learning/wordbooks/' + book.id + '/reset')
    historyItem.value = null; history.value = []
    const resetItems = await loadItems(book.id)
    if (!resetItems) throw new Error('重置后状态尚未读取，请重新核对学习状态。')
    if (owner) await invalidateLearningProgress(owner, Object.fromEntries(resetItems.map(item => [item.id, item.progressEpoch])))
    await loadWordbooks(); await loadPendingCount(); message.value = '学习进度已重置。'
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '进度重置失败' }
  finally { busy.value = false }
}
/** 从当前单词本移除一个词条；其他单词本的共享进度不会受影响。 */
async function removeItem(item: LearningItem) {
  if (!online.value) return
  if (!selectedId.value || !window.confirm(`从当前单词本移除“${item.written}”吗？`)) return
  const bookId = selectedId.value
  busy.value = true; error.value = ''; message.value = ''
  try {
    const owner = scope.value
    const deleted = await deleteJson<{ deletedLearningItemIds: string[] }>('/api/v1/learning/wordbooks/' + bookId + '/items/' + item.id)
    if (owner) await purgeDeletedPrivateCache(owner, deleted.deletedLearningItemIds, deleted.deletedLearningItemIds.includes(item.id) && item.personalCustomEntryId ? [item.personalCustomEntryId] : [])
    if (historyItem.value?.id === item.id) { historyItem.value = null; history.value = [] }
    await loadWordbooks()
    if (owner) await reconcileOfflineMembership(owner, bookId, bookItems.value[bookId] ?? null)
    await loadPendingCount(); message.value = '词条已移除。'
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '词条移除失败' }
  finally { busy.value = false }
}
/** 详情放入路由参数，返回总览、刷新和浏览器后退都能保持清晰的位置。 */
watch(() => route.query.book, value => {
  historyItem.value = null; filter.value = 'all'
  selectedId.value = wordbooks.value.some(book => book.id === value) ? String(value) : ''
  items.value = bookItems.value[selectedId.value] ?? []
})
/** 弹窗关闭不清空未提交输入；保存成功才清空，防止误关丢失录入。 */
function openCreate() { error.value = ''; createDialog.value?.showModal() }
onBeforeRouteLeave(() => { createDialog.value?.close(); wordbookEditor.value?.close() })
/** Ionic 可保留页面实例；切换账号先清空展示，旧账号网络响应不能写入新展示。 */
watch(() => scope.value ? accountKey(scope.value) : '', () => {
  loadSequence++; wordbooks.value = []; bookItems.value = {}; items.value = []; selectedId.value = ''
  historyItem.value = null; issues.value = []; pendingCount.value = 0; resumableBooks.value = []
  createDialog.value?.close()
})
/** 进入页面时在线核对；离线继续读自己的缓存，不清除未知状态的事件。 */
async function enter() { if (online.value) await checkRemote(); else { await loadWordbooks(); await loadPendingCount() } }
onMounted(enter)
onIonViewWillEnter(enter)
</script>
<template>
  <ion-page><ion-content class="study-content"><main class="study-shell">
    <header class="account-bar study-header"><router-link to="/">← 学习首页</router-link><div class="actions"><router-link to="/dictionary">查词典</router-link><router-link to="/private-entries">私有词条</router-link><router-link to="/offline">离线准备</router-link></div></header>
    <p class="brand">LANGUAGE LEAN · 背词</p>
    <div class="section-heading study-title">
      <div><router-link v-if="showingBook" to="/learning" class="study-back">← 背词总览</router-link><h1>{{ showingBook ? selectedBook?.name : '今天，也记住一点。' }}</h1></div>
      <button v-if="!showingBook" :disabled="busy || !online" @click="openCreate">新建单词本</button>
      <details v-else ref="bookMenu" class="book-menu"><summary>管理单词本</summary><div class="book-menu-actions"><button class="quiet" :disabled="busy || !online" @click="openBookEditor">编辑单词本信息</button><button class="quiet" :disabled="busy || !online" @click="resetWordbook">重置进度</button><button class="quiet danger" :disabled="busy || !online" @click="removeWordbook(selectedBook!)">删除单词本</button></div></details>
    </div>
    <p class="intro">{{ showingBook ? selectedBook?.description || '分类管理词条，学习进度跨单词本共享。' : '先复习熟悉的词，再遇见新的词。' }}</p>
    <p v-if="!online" class="note">正在查看当前账号的本地缓存。新建、删除、重置和历史查询需要联网登录。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="feedback" role="status">{{ message }}</p>
    <p v-if="syncNotice" class="feedback" role="status">{{ syncNotice }}</p>
    <p v-if="loading" class="note" role="status">正在加载单词本和学习进度…</p>
    <section v-if="!showingBook" class="study-overview" aria-labelledby="wordbook-list-title">
      <div class="section-heading"><h2 id="wordbook-list-title">我的单词本</h2><span class="study-muted">{{ wordbooks.length }} 个</span></div>
      <p v-if="!loading && !wordbooks.length" class="empty-state">还没有单词本。点击“新建单词本”，再从词典或私有词条加入学习内容。</p>
      <div class="study-book-grid">
        <article v-for="(book, index) in wordbooks" :key="book.id" class="study-book-card">
          <div class="section-heading"><h3>{{ book.name }}</h3><span v-if="resumableBooks.includes(book.id)" class="study-chip">训练未完成</span></div>
          <div class="study-book-body"><div class="study-cover" :class="'cover-' + index % 3" aria-hidden="true"><small>WORD BOOK</small><strong>{{ book.name }}</strong><small>LANGUAGE LEAN</small></div>
            <div class="study-book-summary"><p>{{ book.itemCount }} 个词<span v-if="summaries[book.id]"> · {{ summaries[book.id]!.learned }} 个已学</span></p>
              <div v-if="summaries[book.id]" class="study-progress" role="progressbar" :aria-label="book.name + '已学比例'" aria-valuemin="0" :aria-valuemax="summaries[book.id]!.total || 1" :aria-valuenow="summaries[book.id]!.learned"><i :style="{ width: (summaries[book.id]!.total ? summaries[book.id]!.learned / summaries[book.id]!.total * 100 : 0) + '%' }"></i></div>
              <p class="study-muted">{{ summaries[book.id] ? '待复习 ' + summaries[book.id]!.due + ' · 未学习 ' + summaries[book.id]!.fresh : '学习统计尚未加载' }}</p>
              <router-link :to="{ path: '/learning', query: { book: book.id } }" class="study-list-link">学习列表 →</router-link>
            </div>
          </div>
          <p v-if="book.description" class="study-muted book-description">{{ book.description }}</p>
          <div class="actions study-book-actions"><router-link class="button-link" :to="'/learning/' + book.id + '/review'">{{ resumableBooks.includes(book.id) ? '继续训练' : '开始复习' }}</router-link><router-link class="button-link secondary" :to="{ path: '/learning', query: { book: book.id } }">查看词条</router-link></div>
        </article>
      </div>
    </section>
    <section v-if="!showingBook" aria-labelledby="study-today-title">
      <div class="section-heading"><h2 id="study-today-title">今天的学习</h2><span class="study-muted">同词跨本去重</span></div>
      <div class="study-stats"><div><strong>{{ loading ? '—' : overall?.today ?? '—' }}</strong><span>今日已复习词</span></div><div><strong>{{ loading ? '—' : overall?.due ?? '—' }}</strong><span>待复习</span></div><div><strong>{{ loading ? '—' : overall?.fresh ?? '—' }}</strong><span>未学习</span></div></div>
      <p class="study-muted">已学表示完成过复习，仍会按 FSRS 周期再次出现。</p>
    </section>
    <section v-if="showingBook" aria-labelledby="item-list-title">
      <div class="section-heading"><h2 id="item-list-title">学习列表</h2><span class="study-muted">{{ items.length }} 个词</span></div>
      <div class="actions study-book-actions"><router-link class="button-link" :to="'/learning/' + selectedId + '/review'">{{ resumableBooks.includes(selectedId) ? '继续听音训练' : '开始今日听音训练' }}</router-link><router-link class="button-link secondary" :to="'/learning/' + selectedId + '/review?mode=extra'">额外训练</router-link><router-link class="button-link secondary" :to="'/learning/' + selectedId + '/review?mode=ear'">耳词训练</router-link><LearningCsvImport :key="selectedId" :book-id="selectedId" :online="online" @imported="csvImported" /></div>
      <div class="study-filters" aria-label="词条筛选"><button v-for="option in ([['all', '全部'], ['new', '未学习'], ['due', '待复习'], ['learning', '学习中'], ['focus', '耳词']] as const)" :key="option[0]" :class="{ active: filter === option[0] }" :aria-pressed="filter === option[0]" @click="filter = option[0]">{{ option[1] }}</button></div>
      <p v-if="!loading && !bookItems[selectedId]" class="empty-state">词条尚未加载成功，请点击“重新加载学习状态”后重试。</p>
      <p v-else-if="!loading && !items.length" class="empty-state">当前单词本还没有词条。在词典或私有词条详情中选择这个单词本即可加入。</p>
      <p v-else-if="!visibleItems.length" class="empty-state">这个分类下暂时没有词条。</p>
      <div v-else class="study-word-list"><article v-for="item in visibleItems" :key="item.id" class="study-word-row">
        <div class="study-word-body"><router-link :to="'/learning/items/' + item.id" class="study-word-title">{{ item.written }}</router-link>
          <p class="study-muted">{{ learningStatusLabel(item.status) }} · {{ item.reviewCount }} 次完整复习<span v-if="matchesLearningFilter(item, 'due')"> · 待复习</span><span v-if="item.manualEarFocus || item.automaticEarFocus"> · 听力重点</span></p>
          <p v-if="item.nextReviewAt" class="study-muted">下次复习：{{ displayTime(item.nextReviewAt) }}<span v-if="item.pendingSchedule === 'PROJECTED'"> · 待同步预估</span></p>
          <p v-if="item.pendingSchedule === 'UNAVAILABLE'" class="study-muted">答题待同步，联网后确认复习周期。</p></div>
        <details class="word-menu"><summary :aria-label="'管理词条' + item.written">更多</summary><div class="actions"><button class="quiet" :disabled="busy || !online" @click="showHistory(item)">复习历史</button><button class="quiet" :disabled="busy || !online" @click="toggleManualFocus(item)">{{ item.manualEarFocus ? '取消手动重点' : '加入手动重点' }}</button><button class="quiet danger" :disabled="busy || !online" @click="removeItem(item)">从此单词本移除</button></div></details>
      </article></div>
    </section>
    <section aria-labelledby="progress-upload-title">
      <div class="section-heading"><h2 id="progress-upload-title">学习进度同步</h2><span class="study-chip">{{ pendingCount }} 条待上传</span></div>
      <p class="study-muted">每词完成后自动上传；断网时保存在所属账号的本地缓存。这里可以补传今日及此前未提交的记录。</p>
      <div class="actions"><button class="secondary" :disabled="uploading || !pendingCount" @click="uploadProgress">{{ uploading ? '正在上传…' : '上传今日学习进度' }}</button><button class="quiet" :disabled="checking || loading || !online" @click="checkRemote">{{ checking ? '正在核对…' : '重新加载学习状态' }}</button><router-link to="/login?reauth=1">重新登录学习账号</router-link></div>
    </section>
    <section v-if="issues.length" aria-labelledby="sync-issues-title"><h2 id="sync-issues-title">需要处理的上传记录</h2>
      <p class="study-muted">记录尚未上传成功，可检查基准和设备时间后重试；无法恢复时可明确丢弃。</p>
      <article v-for="issue in issues" :key="issue.eventId" class="entry-row"><strong>{{ items.find(item => item.id === issue.learningItemId)?.written || '学习记录' }}</strong><p class="error">{{ issue.message }}</p><div class="actions"><button class="secondary" :disabled="uploading" @click="uploadProgress">重试上传</button><button class="quiet danger" @click="discardIssue(issue)">丢弃未上传记录</button></div></article>
    </section>
    <section v-if="historyItem" aria-labelledby="review-history-title">
      <div class="section-heading"><h2 id="review-history-title">{{ historyItem.written }} · 复习历史</h2><button class="quiet" @click="historyItem = null">关闭</button></div>
      <p class="study-muted">最近 50 次完整复习，按实际答题时间排序。成功重试仍保留本轮最差评分。</p>
      <p v-if="historyLoading" role="status">正在加载…</p><p v-else-if="!history.length" class="empty-state">还没有完整复习记录。</p>
      <ol v-else class="entry-list"><li v-for="record in history" :key="record.eventId" class="entry-row"><strong>{{ ({ AGAIN: '× 忘记', HARD: '△ 困难', GOOD: '○ 记得' })[record.finalRating] }}</strong><span>答题：{{ displayTime(record.completedAt) }} · 上传：{{ displayTime(record.receivedAt) }}</span><span>下次复习：{{ displayTime(record.nextReviewAt) }}</span></li></ol>
    </section>
    <dialog ref="createDialog" class="config-dialog study-dialog" aria-labelledby="create-wordbook-title" @cancel="busy && $event.preventDefault()">
      <div class="section-heading"><h2 id="create-wordbook-title">新建单词本</h2><button type="button" class="quiet" :disabled="busy" aria-label="关闭新建单词本" @click="createDialog?.close()">关闭</button></div>
      <form class="editor-fields" @submit.prevent="createWordbook"><label>名称<input v-model="name" maxlength="100" required placeholder="例如：旅行日语"></label><label>说明<textarea v-model="description" maxlength="500" rows="3" placeholder="可留空"></textarea></label><p v-if="error" class="error" role="alert">{{ error }}</p><div class="dialog-actions"><button type="button" class="secondary" :disabled="busy" @click="createDialog?.close()">取消</button><button :disabled="busy || !online">{{ busy ? '正在创建…' : '创建单词本' }}</button></div></form>
    </dialog>
    <WordbookEditor ref="wordbookEditor" :scope="scope" :online="online" @saved="wordbookEdited" />
  </main></ion-content></ion-page>
</template>
