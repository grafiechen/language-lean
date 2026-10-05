<script setup lang="ts">
import { computed, ref } from 'vue'
import { IonPage, IonContent, onIonViewWillEnter } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
import type { Wordbook } from '../features/learning/types'
import type { CachedAccountRow, OfflinePreparationRow } from '../platform/web/database'
import { accountKey } from '../platform/web/database'
import { availableCachedAccounts } from '../platform/web/cachedAccounts'
import { networkOnline } from '../platform/web/connectivity'
import { offlinePreparations, prepareOfflineWordbook } from '../platform/web/offlineLearning'
import { getJson } from '../shared/api'
import { reconcileLearning } from '../platform/web/reconciliation'
import { currentReviewScope } from '../platform/web/reviewSync'
import { learningExport, downloadLearningExport } from '../platform/web/learningExport'
import LearningRecoveryDialog from '../features/learning/LearningRecoveryDialog.vue'
import { accountCacheUsage, siteStorageUsage, formatBytes, clearUnusedAudio, clearAccountDownloads, removeOfflineDownload,
  requestStoragePersistence, learningStorageError, type AccountCacheUsage, type SiteStorageUsage } from '../platform/web/cacheManagement'

const auth = useAuth(), accounts = ref<CachedAccountRow[]>([]), rows = ref<OfflinePreparationRow[]>([])
const books = ref<Wordbook[]>([]), busy = ref(false), error = ref(''), done = ref(0), total = ref(0), message = ref('')
const scope = computed(() => auth.user ? { serverId: location.origin, userId: auth.user.id } : null)
const online = computed(() => auth.serverAuthenticated && networkOnline.value)
const usage = ref<AccountCacheUsage | null>(null), storage = ref<SiteStorageUsage | null>(null)
const preparing = ref(false)
/** 离线备份明确只包含本机数据；联网时读取所属账户完整服务器快照。 */
async function exportData() {
  if (!scope.value || busy.value) return
  const owner = { ...scope.value }; busy.value = true; error.value = ''; message.value = ''
  try {
    const value = await learningExport(owner, online.value)
    if (scope.value?.userId !== owner.userId) throw new Error('学习账号已变化，请重新导出。')
    downloadLearningExport(value); message.value = value.includesServerData ? '已导出服务器数据和本机未同步记录。' : '已导出本机缓存及未同步记录，未包含服务器上未下载的数据。'
  } catch (cause) { error.value = learningStorageError(cause, '备份失败，请重试。') }
  finally { busy.value = false }
}
let loadSequence = 0
let usageSequence = 0
/** 浏览器额度读取失败不阻止账号清理；异步结果只能写回其原归属页面。 */
async function refreshUsage() {
  const sequence = ++usageSequence
  const owner = scope.value ? { ...scope.value } : null
  try {
    const [current, site] = await Promise.all([owner ? accountCacheUsage(owner) : Promise.resolve(null), siteStorageUsage()])
    if (sequence === usageSequence && owner?.userId === scope.value?.userId) { usage.value = current; storage.value = site }
  } catch (cause) { if (sequence === usageSequence && owner?.userId === scope.value?.userId) error.value = learningStorageError(cause, '缓存用量读取失败。') }
}
/** 缓存账户和书目分开读取，离线选择不能触发另一登录账号的 API 查询。 */
async function load() {
  const sequence = ++loadSequence, owner = scope.value ? { ...scope.value } : null
  const current = () => sequence === loadSequence && owner?.userId === scope.value?.userId
  try {
    const [cachedAccounts, cachedRows] = await Promise.all([availableCachedAccounts(location.origin),
      owner ? offlinePreparations(owner) : Promise.resolve([])])
    if (!current()) return
    accounts.value = cachedAccounts; rows.value = cachedRows; books.value = cachedRows.map(row => row.book)
    await refreshUsage()
    if (online.value && owner) {
      await reconcileLearning(owner)
      const serverBooks = await getJson<Wordbook[]>('/api/v1/learning/wordbooks')
      const checked = await currentReviewScope(), prepared = await offlinePreparations(owner)
      if (!current()) return
      if (checked.userId !== owner.userId || checked.serverId !== owner.serverId) throw new Error('服务器账号已变化，请重新登录学习账号。')
      books.value = serverBooks; rows.value = prepared; await refreshUsage()
    }
  } catch (cause) { if (current()) error.value = learningStorageError(cause, '离线数据读取失败。') }
}
/** 显式切换本机归属，不改变服务端登录身份。 */
async function select(key: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    if (await auth.selectCached(key)) { usage.value = null; rows.value = []; books.value = []; await load() }
  } catch (cause) { error.value = learningStorageError(cause, '缓存账号切换失败。') }
  finally { busy.value = false }
}
/** 云服务尚未配置或某个资源下载失败时保留可重试状态。 */
async function prepare(book: Wordbook) {
  if (!scope.value || !online.value || busy.value) return
  const owner = { ...scope.value }
  busy.value = true; preparing.value = true; error.value = ''; message.value = ''; done.value = total.value = 0
  try {
    const row = await prepareOfflineWordbook(owner, book, (value, count) => { done.value = value; total.value = count })
    message.value = row.status === 'READY' ? '离线页面、词条和音频已准备，可以断网关闭后重新打开。'
      : row.status === 'EMPTY' ? '此单词本没有可用于听音训练的词，请先补充发音。' : '部分资源未准备成功，请检查原因后重试。'
  } catch (cause) { error.value = learningStorageError(cause, '离线准备失败。') }
  finally { busy.value = false; preparing.value = false; await load() }
}
/** 下载清理与服务器删除区分；未上传答题和进行中的训练始终保留。 */
async function clearDownloads(mode: 'UNUSED' | 'ACCOUNT' | 'BOOK', book?: Wordbook) {
  if (!scope.value || busy.value) return
  const owner = { ...scope.value }
  if (mode !== 'UNUSED' && !window.confirm(mode === 'BOOK'
    ? `移除“${book?.name}”的本机下载吗？未上传答题、未完成训练和服务器进度保留，离线内容需要重新准备。`
    : '清理当前学习账号的本机下载吗？未上传答题和未完成训练保留，其他账号及服务器内容不受影响。')) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const result = await (mode === 'UNUSED' ? clearUnusedAudio(owner) : mode === 'BOOK'
      ? removeOfflineDownload(owner, book!.id) : clearAccountDownloads(owner))
    if (scope.value?.userId === owner.userId) message.value = `已移除 ${result.removedBooks} 个离线范围、${result.removedAudio} 个未引用音频，释放音频内容 ${formatBytes(result.freedAudioBytes)}。学习记录保留。`
  } catch (cause) { error.value = learningStorageError(cause, '缓存清理失败，原数据仍保留。') }
  finally { busy.value = false; await load() }
}
/** 用户主动请求浏览器保留，拒绝时继续使用原缓存。 */
async function persist() {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { message.value = await requestStoragePersistence() ? '浏览器已同意保留本站离线数据。' : '浏览器暂未同意保留，现有缓存仍可使用；请及时同步进度。'; await refreshUsage() }
  catch (cause) { error.value = learningStorageError(cause, '无法申请保留离线数据。') }
  finally { busy.value = false }
}
/** 显示本书当前分区的准备状态。 */
function state(bookId: string) { return rows.value.find(row => row.wordbookId === bookId) }
onIonViewWillEnter(load)
</script>
<template>
  <ion-page><ion-content><main>
    <header class="account-bar"><router-link to="/">← 学习首页</router-link><router-link to="/login?reauth=1">登录后同步</router-link></header>
    <p class="brand">LANGUAGE LEAN · 离线学习</p><h1>离线准备</h1>
    <p class="note">先联网下载单词本，再断网学习。首次访问或未准备内容需要联网。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="feedback" role="status">{{ message }}</p>
    <p v-if="preparing" role="status">准备中：{{ done }} / {{ total }}</p>
    <section v-if="scope" aria-labelledby="learning-export-title"><h2 id="learning-export-title">学习数据备份</h2><p class="note">导出单词本、个人内容、学习进度及本机草稿和待上传记录。离线时只导出本机已有内容；音频文件需另行准备。恢复先核对当前账号和进度，只补回本机学习记录。</p><div class="actions"><button :disabled="busy" @click="exportData">导出学习数据 JSON</button><LearningRecoveryDialog :scope="scope" :online="online" :disabled="busy" @restored="load" /></div></section>
    <section><h2>当前学习账号</h2><p>{{ auth.user?.username || '尚未选择账号' }}{{ auth.mode === 'CACHED' ? ' · 使用本地缓存，上传需重新登录' : '' }}</p>
      <div class="actions"><button v-for="account in accounts" :key="account.accountKey" class="quiet" :disabled="busy || (!!scope && account.accountKey === accountKey(scope))" @click="select(account.accountKey)">使用 {{ account.username }} 的离线内容</button></div>
      <p v-if="!accounts.length" class="note">还没有离线准备或待上传记录，请先登录并准备一个单词本。</p>
    </section>
    <section aria-labelledby="cache-management-title"><div class="section-heading"><h2 id="cache-management-title">本机缓存</h2><button class="quiet" :disabled="busy" @click="refreshUsage">刷新用量</button></div>
      <p class="note">本站占用约 {{ formatBytes(storage?.usage) }} / 浏览器额度约 {{ formatBytes(storage?.quota) }}，包括所有账号与应用页面。以下内容统计只针对当前学习账号。</p>
      <div v-if="usage" class="entry-list">
        <p>{{ usage.preparations }} 个离线范围 · {{ usage.audioCount }} 个音频（{{ formatBytes(usage.audioBytes) }}） · 词条与训练快照约 {{ formatBytes(usage.contentBytes) }}</p>
        <p>{{ usage.pending }} 条待上传 · {{ usage.issues }} 条待处理冲突 · {{ usage.continuing.length }} 个单词本有未完成训练</p>
        <p>可清理：{{ usage.unusedAudioCount }} 个未引用音频（{{ formatBytes(usage.unusedAudioBytes) }}）</p>
        <div class="actions"><button class="secondary" :disabled="busy || !usage.unusedAudioCount" @click="clearDownloads('UNUSED')">清理未引用音频</button><button class="quiet danger" :disabled="busy || (!usage.preparations && !usage.unusedAudioCount)" @click="clearDownloads('ACCOUNT')">清理当前账号下载</button></div>
        <router-link v-for="training in usage.continuing" :key="training.wordbookId" :to="'/learning/' + training.wordbookId + '/review'">继续未完成训练：{{ training.name }}</router-link>
      </div>
      <p class="note">清理下载保留待上传进度、冲突和训练草稿；仍被其他单词本或训练引用的音频保留。再次离线学习需联网准备。</p>
      <p class="note">{{ storage?.persisted ? '浏览器已允许保留本站离线数据。' : '浏览器尚未承诺保留本站离线数据。' }}主动清除浏览器数据仍会移除缓存。</p>
      <button class="secondary" :disabled="busy || !storage?.persistenceAvailable || storage?.persisted" @click="persist">申请保留离线数据</button>
    </section>
    <section><h2>单词本准备状态</h2><p v-if="!books.length" class="empty-state">没有可准备的单词本。</p>
      <article v-for="book in books" :key="book.id" class="entry-row">
        <strong>{{ book.name }}</strong><span>{{ state(book.id) ? ({ PREPARING: '准备中或上次被中断', READY: '已准备', PARTIAL: '部分失败', EMPTY: '没有可训练发音' })[state(book.id)!.status] : '未准备' }}</span>
        <span v-if="state(book.id)">{{ state(book.id)!.words.length }} 个词音频已下载 · {{ state(book.id)!.missingPronunciation }} 个词没有发音</span>
        <ul v-if="state(book.id)?.failures.length" class="error"><li v-for="failure in state(book.id)!.failures" :key="failure">{{ failure }}</li></ul>
        <div class="actions"><button class="secondary" :disabled="busy || !online" @click="prepare(book)">{{ state(book.id) ? '重新准备离线内容' : '准备离线内容' }}</button><button v-if="state(book.id)" class="quiet" :disabled="busy" @click="clearDownloads('BOOK', book)">移除本机下载</button><router-link v-if="state(book.id)?.status === 'READY'" :to="'/learning/' + book.id + '/review'">离线听音训练</router-link></div>
      </article>
    </section>
  </main></ion-content></ion-page>
</template>
