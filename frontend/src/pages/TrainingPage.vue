<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { onBeforeRouteLeave } from 'vue-router'
import { IonPage, IonContent, onIonViewWillEnter } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
import ContentView from '../features/dictionary/ContentView.vue'
import { currentTrainingQuestion, type TrainingBatch } from '../core/training'
import type { Rating } from '../core/reviews'
import type { TrainingWord } from '../features/learning/trainingTypes'
import { cachedAudio } from '../platform/web/audio'
import { prepareTraining, refreshTrainingAudio, restoreTraining, startTraining } from '../platform/web/trainingSessions'
import { recordBrowserTrainingRating, pendingReviews } from '../platform/web/reviewSync'
import { prepareCachedTraining, localLearningItem } from '../platform/web/offlineLearning'
import type { LearningItem } from '../features/learning/types'
import { networkOnline } from '../platform/web/connectivity'
import { reconcileLearning } from '../platform/web/reconciliation'
import { learningStorageError } from '../platform/web/cacheManagement'

const route = useRoute(), auth = useAuth()
const scope = computed(() => auth.user ? { serverId: window.location.origin, userId: auth.user.id } : null)
const batch = ref<TrainingBatch | null>(null), words = ref<Record<string, TrainingWord>>({})
const busy = ref(false), error = ref(''), message = ref(''), failures = ref<string[]>([])
const prepared = ref<TrainingWord[]>([]), missing = ref(0), done = ref(0), total = ref(0)
const revealed = ref(false), heard = ref(false), playing = ref(false), pending = ref(0)
const completedWord = ref<LearningItem | null>(null)
const question = computed(() => batch.value ? currentTrainingQuestion(batch.value) : null)
const word = computed(() => question.value ? words.value[question.value.itemId] : null)
const finished = computed(() => !!batch.value && !question.value)
const earMode = computed(() => batch.value ? batch.value.trainingMode === 'EAR' : route.query.mode === 'ear')
/** 耳词在不同轮次和Again重试间轮换读音，仍只记录一次词条复习。 */
const currentAudio = computed(() => {
  if (!word.value) return null
  const alternatives = word.value.readingAudio
  if (!earMode.value || !alternatives?.length) return { audioVersionId: word.value.audioVersionId, readingId: word.value.readingId }
  const attempts = question.value ? batch.value?.items[question.value.itemId]?.results.find(result => result.typeId === question.value?.type.typeId)?.trials.length ?? 0 : 0
  return alternatives[(word.value.learning.reviewCount + attempts) % alternatives.length]!
})
/** 进度只统计完整通过全部必做题型的词，不把 Again 尝试次数当作完成数。 */
const completedCount = computed(() => batch.value ? Object.values(batch.value.items).filter(item => item.completedAt).length : 0)
const batchCount = computed(() => batch.value ? Object.keys(batch.value.items).length : 0)
const remainingInGroup = computed(() => {
  const group = batch.value?.groups[batch.value.currentGroupIndex]
  return group ? group.pendingQueue.length + group.retryQueue.length : 0
})
let player: HTMLAudioElement | null = null, objectUrl = '', questionSequence = 0, disposed = false
let active = true

/** 音频准备与学习完成分开，失败资源不会被跳过或记分。 */
async function prepare() {
  if (!scope.value || busy.value) return
  const owner = { ...scope.value }, bookId = String(route.params.wordbookId), extra = route.query.mode === 'extra' || earMode.value
  busy.value = true; error.value = ''; failures.value = []; message.value = ''
  try {
    const result = auth.serverAuthenticated && networkOnline.value
      ? await prepareTraining(owner, bookId, extra, (value, count) => { done.value = value; total.value = count }, { earFocus: earMode.value, allReadings: earMode.value })
      : await prepareCachedTraining(owner, bookId, extra, earMode.value)
    if (!active || scope.value?.userId !== owner.userId) return
    prepared.value = result.words; missing.value = result.missingPronunciation; failures.value = result.failures
    if (!result.words.length && !result.failures.length) message.value = '当前没有可以进行听音训练的词条。'
  } catch (cause) { error.value = learningStorageError(cause, '训练准备失败。') }
  finally { busy.value = false }
}
/** 用户明确开始后固定基准和资源；先写入缓存，再展示第一题。 */
async function start() {
  if (!scope.value || !prepared.value.length || failures.value.length || busy.value) return
  const owner = { ...scope.value }, bookId = String(route.params.wordbookId)
  busy.value = true; error.value = ''
  try {
    const created = await startTraining(owner, bookId, prepared.value, earMode.value ? 'EAR' : route.query.mode === 'extra' ? 'EXTRA' : 'REVIEW')
    if (!active || scope.value?.userId !== owner.userId) return
    words.value = Object.fromEntries(prepared.value.map(value => [value.learning.id, value])); batch.value = created
  } catch (cause) { error.value = learningStorageError(cause, '训练缓存保存失败。') }
  finally { busy.value = false }
}
/** 新批次另存；上一轮待上传事件及确认关联继续保留。 */
async function prepareNext() {
  if (busy.value) return
  batch.value = null; words.value = {}; prepared.value = []; completedWord.value = null
  await prepare()
}
/** 固定版本缓存缺失或播放失败时关闭答案/评分入口。 */
async function play() {
  if (!active || !scope.value || !word.value || !question.value) return
  const sequence = ++questionSequence
  stop(); heard.value = false; playing.value = false; error.value = ''
  try {
    const blob = await cachedAudio(scope.value, currentAudio.value!.audioVersionId)
    if (!blob) throw new Error('该题音频缓存缺失，请联网重新准备，当前题不计分。')
    if (sequence !== questionSequence || disposed || !active) return
    objectUrl = URL.createObjectURL(blob); player = new Audio(objectUrl)
    player.onended = () => { if (sequence === questionSequence) playing.value = false }
    player.onerror = () => { if (sequence === questionSequence) { heard.value = false; playing.value = false; error.value = '音频播放失败，当前题不计分。请重新准备音频。' } }
    await player.play()
    if (sequence === questionSequence && !disposed) { heard.value = true; playing.value = true }
  } catch (cause) {
    if (sequence === questionSequence && !disposed) error.value = cause instanceof DOMException && cause.name === 'NotAllowedError'
      ? '请点击“播放发音”开始听音。' : cause instanceof Error ? cause.message : '音频播放失败。'
  }
}
/** 保存成功才推进；上传失败保留答题和待上传事件。 */
async function rate(rating: Rating) {
  if (!scope.value || !batch.value || !question.value || !heard.value || !revealed.value || busy.value) return
  const owner = { ...scope.value }
  const completedItem = word.value?.learning
  busy.value = true; error.value = ''
  try {
    const saved = await recordBrowserTrainingRating(owner, batch.value.batchId, question.value.itemId, rating)
    if (!active || scope.value?.userId !== owner.userId) return
    batch.value = saved.batch
    if (saved.event && completedItem) completedWord.value = await localLearningItem(owner, completedItem)
    if (saved.upload) void saved.upload.then(async result => {
      if (disposed || !active || scope.value?.userId !== owner.userId) return
      pending.value = result.remaining
      if (result.remaining) message.value = '答题已保存，仍有记录等待联网或重新登录后上传。'
      if (completedItem && completedWord.value?.lastReviewEventId === saved.event?.eventId) {
        const current = await localLearningItem(owner, completedItem)
        if (!disposed && active && scope.value?.userId === owner.userId
          && completedWord.value?.lastReviewEventId === saved.event?.eventId) completedWord.value = current
      }
    }).catch(() => { if (!disposed && active && scope.value?.userId === owner.userId) message.value = '答题已保存，网络恢复后可在单词本页补传。' })
    pending.value = (await pendingReviews.list(owner)).length
  } catch (cause) { error.value = learningStorageError(cause, '本次判定未保存，请重试。') }
  finally { busy.value = false }
}
/** 重取损坏或缺失的音频并保持原队列，未成功播放前仍禁止评分。 */
async function repairAudio() {
  if (!scope.value || !batch.value || !question.value || busy.value) return
  if (!auth.serverAuthenticated || !networkOnline.value) { error.value = '请联网并登录当前学习账号后重新准备音频。'; return }
  const owner = { ...scope.value }, batchId = batch.value.batchId, itemId = question.value.itemId
  busy.value = true; heard.value = false; revealed.value = false; error.value = ''
  try {
    const updated = await refreshTrainingAudio(owner, batchId, itemId, currentAudio.value?.readingId)
    if (active && scope.value?.userId === owner.userId && question.value?.itemId === itemId) {
      words.value[itemId] = updated
      await play()
    }
  } catch (cause) { error.value = learningStorageError(cause, '音频重新准备失败。') }
  finally { busy.value = false }
}
/** 切题隐藏答案；最后一词 Again 也自动重新播放。 */
watch(question, () => { revealed.value = false; heard.value = false; error.value = ''; if (question.value) void play(); else stop() })
/** 恢复原分组和重试队列。 */
async function restore() {
  if (!scope.value || busy.value) return
  const owner = { ...scope.value }, bookId = String(route.params.wordbookId)
  active = true
  busy.value = true
  try {
    if (auth.serverAuthenticated && networkOnline.value) await reconcileLearning(owner)
    const restored = await restoreTraining(owner, bookId)
    if (!active || scope.value?.userId !== owner.userId) return
    if (restored) { words.value = restored.words; batch.value = restored.batch; message.value = '已恢复上次退出时的训练。' }
    else { batch.value = null; words.value = {}; prepared.value = []; failures.value = [] }
    pending.value = (await pendingReviews.list(owner)).length
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '训练草稿读取失败。' }
  finally { busy.value = false }
}
onMounted(restore)
/** 离开保留草稿，只释放媒体资源。 */
function stop() { player?.pause(); player = null; if (objectUrl) URL.revokeObjectURL(objectUrl); objectUrl = '' }
onBeforeUnmount(() => { disposed = true; questionSequence++; stop() })
onBeforeRouteLeave(() => { active = false; questionSequence++; stop() })
onIonViewWillEnter(() => { active = true; void restore() })
</script>
<template>
  <ion-page><ion-content class="study-content"><main class="study-shell training-shell">
    <header class="account-bar study-header"><router-link :to="{ path: '/learning', query: { book: String(route.params.wordbookId) } }">← {{ question ? '暂停返回' : '学习列表' }}</router-link><span class="study-muted">{{ pending }} 条待上传</span></header>
    <p class="brand">LANGUAGE LEAN · 听音回忆</p>
    <h1 v-if="!question">{{ finished ? '这一轮，完成了。' : '准备开始听音训练' }}</h1>
    <div v-if="batch" class="training-progress-header"><strong>{{ earMode ? '耳词训练' : route.query.mode === 'extra' ? '额外训练' : '听音复习' }}</strong><span>{{ completedCount }} / {{ batchCount }} 词完成</span></div>
    <div v-if="batch" class="study-progress" role="progressbar" aria-label="本次训练完成进度" aria-valuemin="0" :aria-valuemax="batchCount || 1" :aria-valuenow="completedCount"><i :style="{ width: (batchCount ? completedCount / batchCount * 100 : 0) + '%' }"></i></div>
    <p v-if="question && batch" class="training-stage study-muted">第 {{ batch.currentGroupIndex + 1 }} / {{ batch.groups.length }} 组 · 本组剩余 {{ remainingInGroup }} 词 · 每组最多 10 词</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="feedback" role="status">{{ message }}</p>
    <router-link v-if="error" to="/offline">管理离线缓存</router-link>
    <p v-if="completedWord?.pendingSchedule === 'PROJECTED'" class="feedback" role="status">{{ completedWord.written }} · 下次复习（待同步）：{{ new Date(completedWord.nextReviewAt!).toLocaleString() }}</p>
    <p v-else-if="completedWord?.pendingSchedule === 'UNAVAILABLE'" class="study-muted">答题已缓存；离线调度参数尚未完整准备，上传后确认下次复习时间。</p>
    <section v-if="!batch" class="training-prepare">
      <h2>准备训练资源</h2><p class="study-muted">{{ earMode ? '复习此单词本的自动耳词和手动重点，包含未到期词；轮换读音，完成后更新同一份FSRS进度。' : route.query.mode === 'extra' ? '额外训练包含未到期词，完成后同样更新复习进度。' : '到期词优先，再学新词；未到期词不进入本次清单。' }}</p>
      <p v-if="!auth.serverAuthenticated || !networkOnline" class="study-muted">使用已准备的离线内容，按本地 FSRS 周期纳入到期词。旧缓存缺少算法参数时，待上传词需联网确认周期，或选择额外训练。</p>
      <p v-if="busy" role="status">准备中：{{ done }} / {{ total }}</p><p v-if="missing" class="study-muted">{{ missing }} 个词没有填写发音，留在单词本中等待补充，不计入听音训练。</p>
      <ul v-if="failures.length" class="error" role="alert"><li v-for="failure in failures" :key="failure">{{ failure }}</li></ul>
      <p v-if="prepared.length" class="training-ready">{{ prepared.length }} 个词的内容和音频已准备。</p>
      <div class="actions"><button class="secondary" :disabled="busy" @click="prepare">{{ failures.length ? '重试准备音频' : '准备内容和音频' }}</button><button :disabled="busy || !prepared.length || !!failures.length" @click="start">开始听音训练</button></div>
    </section>
    <section v-else-if="question && word" class="training-question study-training-card">
      <div v-if="!revealed" class="training-hidden-answer">
        <h2>听发音，回忆单词和意思</h2>
        <p class="study-muted">先凭声音回忆，再翻面核对。</p>
        <button class="training-audio" :disabled="busy" :aria-label="playing ? '重新播放发音' : '播放发音'" @click="play"><svg aria-hidden="true" width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M11 4 5 9H2v6h3l6 5V4Z"/><path d="M15 8a6 6 0 0 1 0 8M18 5a10 10 0 0 1 0 14"/></svg></button>
        <p class="study-muted">{{ playing ? '正在播放发音' : heard ? '可以再次播放' : '点击播放发音' }}</p>
        <button v-if="error && !heard" class="quiet" :disabled="busy" @click="repairAudio">重新准备当前音频</button>
        <button class="training-reveal" :disabled="busy || !heard" @click="revealed = true">显示答案</button>
      </div>
      <div v-else class="training-answer study-training-answer"><h2>{{ word.entry.written }}</h2>
        <button class="quiet" :disabled="busy" @click="play">{{ playing ? '重新播放发音' : '播放发音' }}</button>
        <button v-if="error && !heard" class="quiet" :disabled="busy" @click="repairAudio">重新准备当前音频</button>
        <p v-if="word.personal?.meaningOverride !== null && word.personal?.meaningOverride !== undefined" class="study-muted">显示我的个人释义</p>
        <ContentView v-if="word.entry.content" :content="word.entry.content" />
        <div v-if="word.personal?.notes || word.personal?.tags.length" class="training-personal-note"><p v-if="word.personal?.notes" class="preserve-lines">学习笔记：{{ word.personal.notes }}</p><p v-if="word.personal?.tags.length" class="study-muted">标签：{{ word.personal.tags.join('、') }}</p></div>
      </div>
      <div v-if="revealed" class="training-ratings" aria-label="回忆评分"><button class="rating-again" :disabled="busy || !heard" @click="rate('AGAIN')"><strong>×</strong><span>忘记</span></button><button :disabled="busy || !heard" @click="rate('HARD')"><strong>△</strong><span>困难</span></button><button class="rating-good" :disabled="busy || !heard" @click="rate('GOOD')"><strong>○</strong><span>记得</span></button></div>
      <p class="study-muted training-rule">忘记会自动排到本组队尾重试；通过后仍保留本轮最差评分。</p>
    </section>
    <section v-else-if="finished" class="training-finish"><div class="training-complete-icon" aria-hidden="true">✓</div><h2>本次训练已完成</h2><p>{{ completedCount }} 个词的答题已保存。</p><p class="study-muted">{{ pending ? '有 ' + pending + ' 条进度待上传，可返回单词本补传。' : '已确认的进度会清理本地过程记录。' }}</p><div class="actions"><button class="secondary" :disabled="busy" @click="prepareNext">准备下一轮训练</button><router-link class="button-link" :to="{ path: '/learning', query: { book: String(route.params.wordbookId) } }">返回单词本</router-link></div></section>
  </main></ion-content></ion-page>
</template>
