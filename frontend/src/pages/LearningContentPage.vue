<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { IonPage, IonContent, onIonViewWillEnter } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
import ContentView from '../features/dictionary/ContentView.vue'
import ContentEditor from '../features/dictionary/ContentEditor.vue'
import { emptyContent, type DictionaryContent } from '../features/dictionary/types'
import { getJson, putJson } from '../shared/api'
import { learningAudioSource, parsePersonalTags, type LearningContent, type SavePersonalContent } from '../features/learning/personalContent'
import { cachedLearningContent, invalidatePersonalContentDownloads } from '../platform/web/personalContent'
import { networkOnline } from '../platform/web/connectivity'
import { currentReviewScope } from '../platform/web/reviewSync'
import { learningStorageError } from '../platform/web/cacheManagement'
import { trainableStatus } from '../features/learning/types'

const route = useRoute(), auth = useAuth()
const detail = ref<LearningContent | null>(null), loading = ref(false), saving = ref(false)
const error = ref(''), message = ref(''), customMeaning = ref(false), meaning = ref(''), notes = ref(''), tags = ref('')
const customReadings = ref(false), customSenses = ref(false), structured = ref<DictionaryContent>(emptyContent())
const online = computed(() => auth.serverAuthenticated && networkOnline.value)
const scope = computed(() => auth.user ? { serverId: location.origin, userId: auth.user.id } : null)
const inheritedContent = computed(() => detail.value?.entry.originType === 'PRIVATE' ? '私有词条' : '公开词条')
let sequence = 0
/** 每次成功读取或保存后固定表单内容基准，不用 FSRS 版本代替个人内容版本。 */
function show(value: LearningContent) {
  detail.value = value; customMeaning.value = value.personal.meaningOverride !== null
  meaning.value = value.personal.meaningOverride ?? ''; notes.value = value.personal.notes; tags.value = value.personal.tags.join('，')
  customReadings.value = value.personal.readingsOverride != null; customSenses.value = value.personal.sensesOverride != null
  structured.value = JSON.parse(JSON.stringify(value.entry.content ?? emptyContent())) as DictionaryContent
}
/** 在线校验服务端归属，断网只查看上次下载内容；迟到响应不能覆盖另一账号或路由。 */
async function load() {
  const current = ++sequence, owner = scope.value ? { ...scope.value } : null, id = String(route.params.itemId)
  loading.value = true; error.value = ''; message.value = ''; detail.value = null
  const active = () => current === sequence && scope.value?.userId === owner?.userId && String(route.params.itemId) === id
  try {
    if (!owner) throw new Error('请先选择学习账号。')
    const value = online.value ? await getJson<LearningContent>('/api/v1/learning/items/' + id + '/content')
      : await cachedLearningContent(owner, id)
    if (online.value) {
      const verified = await currentReviewScope()
      if (verified.userId !== owner.userId || verified.serverId !== owner.serverId) throw new Error('请登录当前学习账号。')
    }
    if (!active()) return
    if (!value || value.learningItemId !== id) throw new Error('本机没有此词的内容快照，请联网查看或准备单词本。')
    show(value)
  } catch (cause) { if (active()) error.value = learningStorageError(cause, '学习内容加载失败。') }
  finally { if (active()) loading.value = false }
}
/** 保存成功立即更新表单基准；缓存失效失败不把服务端成功误报成未保存。 */
async function save(clear = false) {
  if (!scope.value || !detail.value || !online.value || saving.value || loading.value) return
  if (clear && !window.confirm(`清空个人读音、词义、例句、笔记和标签吗？将恢复沿用${inheritedContent.value}内容，学习进度保留。`)) return
  const owner = { ...scope.value }, id = detail.value.learningItemId, current = sequence
  const active = () => current === sequence && scope.value?.userId === owner.userId && String(route.params.itemId) === id
  saving.value = true; error.value = ''; message.value = ''
  let saved = false
  const previousAudioRevision = detail.value.personal.audioRevision ?? 0
  try {
    const verified = await currentReviewScope()
    if (verified.userId !== owner.userId || verified.serverId !== owner.serverId) throw new Error('请登录当前学习账号后保存。')
    if (!active()) return
    const request: SavePersonalContent = { expectedRevision: detail.value.personal.revision,
      meaningOverride: clear || customSenses.value || !customMeaning.value ? null : meaning.value, notes: clear ? '' : notes.value, tags: clear ? [] : parsePersonalTags(tags.value),
      replaceStructuredContent: true, readingsOverride: clear || !customReadings.value ? null : structured.value.readings,
      sensesOverride: clear || !customSenses.value ? null : structured.value.senses }
    const value = await putJson<LearningContent>('/api/v1/learning/items/' + id + '/content', request)
    saved = true
    if (active()) { show(value); message.value = clear ? '个人内容已清空，学习进度保留。' : '个人内容已保存，学习进度保留。' }
    const audioChanged = previousAudioRevision !== (value.personal.audioRevision ?? 0)
    await invalidatePersonalContentDownloads(owner, id, value.personal.revision, audioChanged ? value.personal.audioRevision ?? 0 : undefined)
    if (active()) message.value += audioChanged ? '个人读音或例句已变化，请重新准备训练；已完成的待上传记录保留。'
      : '已下载的单词本需要重新准备；正在进行的训练继续使用原快照。'
  } catch (cause) { if (active()) error.value = (saved ? '服务器已保存，本机缓存更新失败：' : '') + learningStorageError(cause, '个人内容保存失败。') }
  finally { if (active()) saving.value = false }
}
watch(() => [route.params.itemId, auth.user?.id], () => { saving.value = false; void load() })
onIonViewWillEnter(load)
</script>
<template>
  <ion-page><ion-content><main>
    <header class="account-bar"><router-link to="/learning">← 我的单词本</router-link><router-link to="/offline">离线缓存</router-link></header>
    <p class="brand">LANGUAGE LEAN · 我的词条内容</p><h1>{{ detail?.entry.written || '学习内容' }}</h1>
    <p class="intro">个人内容只属于当前学习账号，在不同单词本之间共享，不修改基准词典。</p>
    <p v-if="!online" class="note">离线显示上次下载的快照，可能与服务器最新内容不同。修改需要联网并登录原账号。</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" :disabled="loading || saving" @click="load">重新加载内容</button></p>
    <p v-if="message" class="feedback" role="status">{{ message }}</p><p v-if="loading" role="status">正在加载…</p>
    <template v-if="detail">
      <section><div class="section-heading"><h2>当前学习内容</h2><router-link v-if="detail.entry.originType === 'PRIVATE' && online" :to="'/private-entries?edit=' + detail.entry.id">编辑私有词条</router-link><router-link v-else-if="detail.entry.originType !== 'PRIVATE'" :to="'/dictionary/' + detail.entry.id">查看公开基准词条</router-link></div>
        <p v-if="!trainableStatus(detail.entry.status)" class="error">此词条已封禁或不可用，不参与训练。</p>
        <template v-else><p v-if="detail.personal.meaningOverride !== null || detail.personal.readingsOverride != null || detail.personal.sensesOverride != null" class="note">以下内容包含我的个人覆盖；未覆盖部分沿用{{ inheritedContent }}。</p>
          <ContentView v-if="detail.entry.content" :content="detail.entry.content" :word-audio="online ? learningAudioSource(detail, 'WORD') : undefined" :example-audio="online ? learningAudioSource(detail, 'EXAMPLE') : undefined" />
        </template>
        <p v-if="detail.personal.notes" class="preserve-lines">学习笔记：{{ detail.personal.notes }}</p>
        <p v-if="detail.personal.tags.length" class="note">标签：{{ detail.personal.tags.join('、') }}</p>
        <router-link v-if="online && detail.entry.originType !== 'PRIVATE' && detail.entry.status === 'PUBLISHED'" :to="{ path: '/contributions', query: { learningItem: detail.learningItemId } }">提交公开修订</router-link>
      </section>
      <section><h2>编辑我的内容</h2><p class="note">未覆盖部分沿用{{ inheritedContent }}的最新内容。简单个人释义和完整词义/例句二选一；笔记与标签仅自己可见。</p>
        <form class="editor-fields" @submit.prevent="save()"><fieldset :disabled="!online || loading || saving" class="personal-content-fields">
          <template v-if="detail.entry.originType !== 'PRIVATE'">
            <label class="checkbox-label"><input v-model="customReadings" type="checkbox">使用个人读音</label>
            <label class="checkbox-label"><input v-model="customSenses" type="checkbox">使用个人词义与例句</label>
            <p v-if="customReadings || customSenses" class="note">首次启用以当前内容为起点；此后的修改只影响自己。删除全部读音后，此词不参加听音训练。</p>
            <ContentEditor v-if="customReadings || customSenses" v-model="structured" personal :edit-readings="customReadings" :edit-senses="customSenses" :edit-attribution="false" />
          </template>
          <label v-if="!customSenses" class="checkbox-label"><input v-model="customMeaning" type="checkbox">使用个人释义</label>
          <label v-if="customMeaning && !customSenses">个人释义<textarea v-model="meaning" required maxlength="4000" rows="4" placeholder="填写适合自己记忆的解释"></textarea></label>
          <label>学习笔记<textarea v-model="notes" maxlength="10000" rows="4" placeholder="仅自己可见"></textarea></label>
          <label>标签<input v-model="tags" placeholder="使用逗号分隔，例如：旅行，动物"><small class="field-note">最多20个，每个不超过50字。</small></label>
          <div class="actions"><button type="submit">{{ saving ? '正在保存…' : '保存个人内容' }}</button><button type="button" class="quiet danger" @click="save(true)">清空个人内容</button></div>
        </fieldset></form>
      </section>
    </template>
  </main></ion-content></ion-page>
</template>
<style scoped>
.personal-content-fields { display: grid; gap: 1rem; border: 0; margin: 0; padding: 0; min-width: 0; }
</style>
