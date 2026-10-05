<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { IonPage, IonContent, onIonViewWillEnter, onIonViewWillLeave } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
import { deleteJson, getJson, postJson, putJson } from '../shared/api'
import { emptyContent, type DictionaryContent, type Results } from '../features/dictionary/types'
import ContentEditor from '../features/dictionary/ContentEditor.vue'
import type { PrivateEntry } from '../features/learning/privateEntries'
import type { LearningItem, Wordbook } from '../features/learning/types'
import type { Language } from '../features/languages/store'
import { currentReviewScope } from '../platform/web/reviewSync'
import { reconcileLearning } from '../platform/web/reconciliation'
import { purgeDeletedPrivateCache } from '../platform/web/personalContent'
import { networkOnline } from '../platform/web/connectivity'

const auth = useAuth(), route = useRoute(), q = ref(''), page = ref(0)
const rows = ref<Results<PrivateEntry>>({ items: [], total: 0, page: 0 }), languages = ref<Language[]>([]), books = ref<Wordbook[]>([])
const bookId = ref(''), loading = ref(false), saving = ref(false), error = ref(''), message = ref(''), editorError = ref('')
const dialog = ref<HTMLDialogElement | null>(null), editing = ref<PrivateEntry | null>(null), editorOpen = ref(false)
const language = ref('ja'), script = ref('Jpan'), written = ref(''), content = ref<DictionaryContent>(emptyContent())
const online = computed(() => auth.serverAuthenticated && networkOnline.value)
let sequence = 0, editorOwner = ''
/** 私人列表仅接收当前账号的完整结果，切换身份时清空旧显示。 */
async function load(index = 0, openQuery = true) {
  const current = ++sequence, owner = auth.user?.id
  loading.value = true; error.value = ''; rows.value = { items: [], total: 0, page: index }
  const active = () => current === sequence && owner === auth.user?.id
  try {
    if (!online.value || !owner) throw new Error('请联网并登录后管理私有词条。')
    if ((await currentReviewScope()).userId !== owner) throw new Error('登录账号已变化，请重新登录。')
    const [result, available, wordbooks] = await Promise.all([
      getJson<Results<PrivateEntry>>('/api/v1/learning/private-entries?' + new URLSearchParams({ q: q.value, page: String(index) })),
      getJson<Language[]>('/api/v1/languages'), getJson<Wordbook[]>('/api/v1/learning/wordbooks')])
    if ((await currentReviewScope()).userId !== owner) throw new Error('登录账号已变化，请重新登录。')
    if (!active()) return
    rows.value = result; languages.value = available; books.value = wordbooks; page.value = index
    if (!wordbooks.some(book => book.id === bookId.value)) bookId.value = wordbooks[0]?.id ?? ''
    if (openQuery && typeof route.query.edit === 'string') {
      const value = await getJson<PrivateEntry>('/api/v1/learning/private-entries/' + encodeURIComponent(route.query.edit))
      if (active()) openEditor(value)
    }
  } catch (cause) { if (active()) error.value = cause instanceof Error ? cause.message : '私有词条加载失败。' }
  finally { if (active()) loading.value = false }
}
/** 新增及编辑统一使用弹窗；编辑保持语言、书写系统和词条UUID稳定。 */
function openEditor(value: PrivateEntry | null = null) {
  if (!online.value || saving.value) return
  editing.value = value; editorOwner = auth.user!.id; editorError.value = ''
  language.value = value?.languageCode ?? languages.value.find(item => item.code === 'ja')?.code ?? languages.value[0]?.code ?? 'ja'
  script.value = value?.scriptCode ?? (language.value === 'ja' ? 'Jpan' : 'Latn'); written.value = value?.written ?? ''
  content.value = value ? JSON.parse(JSON.stringify(value.content)) as DictionaryContent : emptyContent()
  editorOpen.value = true; if (!dialog.value?.open) dialog.value?.showModal()
}
/** 冲突后主动读取新版本，不让用户反复提交同一个陈旧基准。 */
async function reloadEditor() {
  if (!editing.value || saving.value) return
  const owner = editorOwner, id = editing.value.id; saving.value = true; editorError.value = ''
  try {
    const value = await getJson<PrivateEntry>('/api/v1/learning/private-entries/' + id)
    if (owner !== auth.user?.id || (await currentReviewScope()).userId !== owner) throw new Error('登录账号已变化，请重新加载页面。')
    saving.value = false; openEditor(value)
  } catch (cause) { editorError.value = cause instanceof Error ? cause.message : '重新加载失败。' }
  finally { saving.value = false }
}
/** 保存时绑定录入账号，不能将另一页面切换后的会话当作原表单归属。 */
async function save() {
  if (!online.value || saving.value || editorOwner !== auth.user?.id) return
  saving.value = true; editorError.value = ''; error.value = ''; message.value = ''
  let saved: PrivateEntry | null = null
  try {
    if ((await currentReviewScope()).userId !== editorOwner) throw new Error('登录账号已变化，请重新加载后录入。')
    const owner = editorOwner
    saved = editing.value ? await putJson<PrivateEntry>('/api/v1/learning/private-entries/' + editing.value.id,
      { version: editing.value.version, written: written.value, content: content.value })
      : await postJson<PrivateEntry>('/api/v1/learning/private-entries', { languageCode: language.value, scriptCode: script.value, written: written.value, content: content.value }, { 'X-Learning-Account': owner })
    if (owner !== auth.user?.id) return
    editing.value = saved
    message.value = '私有词条已保存，仅自己可见。'
    await reconcileLearning({ serverId: location.origin, userId: owner })
    editorOpen.value = false; dialog.value?.close(); await load(page.value, false)
  } catch (cause) { editorError.value = (saved ? '词条已保存，学习缓存核对未完成：' : '') + (cause instanceof Error ? cause.message : '保存失败。') }
  finally { saving.value = false }
}
/** 分类加入不复制私人内容或进度，重复操作由服务端幂等处理。 */
async function add(value: PrivateEntry) {
  if (!bookId.value || saving.value || !online.value) return
  saving.value = true; error.value = ''; message.value = ''
  try { await postJson<LearningItem>('/api/v1/learning/wordbooks/' + bookId.value + '/private-entries/' + value.id); message.value = '私有词条已加入所选单词本。' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加入失败。' }
  finally { saving.value = false }
}
/** 彻底删除与从某本移除不同，用户确认后清理所有共享身份的本地缓存。 */
async function remove(value: PrivateEntry) {
  if (!online.value || saving.value || !window.confirm(`彻底删除“${value.written}”吗？所有单词本关联、学习进度和私人内容都将删除。`)) return
  saving.value = true; error.value = ''; message.value = ''; const owner = auth.user!.id
  let serverDeleted = false
  try {
    const result = await deleteJson<{ deletedLearningItemIds: string[] }>('/api/v1/learning/private-entries/' + value.id)
    serverDeleted = true
    await purgeDeletedPrivateCache({ serverId: location.origin, userId: owner }, result.deletedLearningItemIds, [value.id])
    await reconcileLearning({ serverId: location.origin, userId: owner })
    if (owner !== auth.user?.id) return
    message.value = '私有词条及学习数据已删除。'; await load(page.value, false)
  } catch (cause) {
    if (owner !== auth.user?.id) return
    if (serverDeleted) rows.value.items = rows.value.items.filter(entry => entry.id !== value.id)
    error.value = (serverDeleted ? '服务器已删除词条，本机缓存清理或核对未完成：' : '') + (cause instanceof Error ? cause.message : '删除失败。')
  }
  finally { saving.value = false }
}
/** 保存期间禁止关闭，避免将写入结果误认为取消。 */
function close() { if (!saving.value) { editorOpen.value = false; dialog.value?.close() } }
watch(() => auth.user?.id, () => { sequence++; editorOpen.value = false; dialog.value?.close(); books.value = []; languages.value = []; void load() })
onIonViewWillEnter(() => load())
onIonViewWillLeave(() => { sequence++; editorOpen.value = false; dialog.value?.close() })
onBeforeUnmount(() => { sequence++; dialog.value?.close() })
</script>
<template>
  <ion-page><ion-content><main>
    <header class="account-bar"><router-link to="/">← 学习首页</router-link><router-link to="/learning">我的单词本</router-link></header>
    <p class="brand">LANGUAGE LEAN · 私有内容</p><h1>我的私有词条</h1>
    <p class="intro">可以先只有写法，再补充读音、释义和例句。只有自己可见，加入多个单词本共享学习进度。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="feedback" role="status">{{ message }}</p>
    <section><div class="section-heading"><h2>私有词条列表</h2><button :disabled="loading || saving || !online" @click="openEditor()">新增私有词条</button></div>
      <form class="search-form" @submit.prevent="load(0, false)"><label>查询写法<input v-model="q" maxlength="200"></label><button class="secondary" :disabled="loading || saving">查询</button></form>
      <label v-if="books.length">加入的单词本<select v-model="bookId" :disabled="saving"><option v-for="book in books" :key="book.id" :value="book.id">{{ book.name }}</option></select></label>
      <p v-else class="note">先去“我的单词本”创建分类，即可加入词条并训练。</p>
      <p v-if="loading" role="status">正在加载…</p><p v-else-if="!rows.items.length" class="empty-state">没有匹配的私有词条。</p>
      <div class="entry-list"><article v-for="entry in rows.items" :key="entry.id" class="entry-row"><strong>{{ entry.written }}</strong><span>{{ entry.languageCode }} · 私有词条</span>
        <div class="actions"><button class="secondary" :disabled="saving || !online" @click="openEditor(entry)">编辑词条</button><button class="quiet" :disabled="saving || !bookId || !online" @click="add(entry)">加入单词本</button><router-link v-if="online" :to="{ path: '/contributions', query: { privateEntry: entry.id } }">提交审核</router-link><button class="quiet danger" :disabled="saving || !online" @click="remove(entry)">彻底删除</button></div>
      </article></div>
      <div class="pagination"><button class="secondary" :disabled="loading || saving || page === 0" @click="load(page - 1, false)">上一页</button><span>{{ rows.total }} 个 · 第 {{ page + 1 }} 页</span><button class="secondary" :disabled="loading || saving || (page + 1) * 20 >= rows.total" @click="load(page + 1, false)">下一页</button></div>
    </section>
    <dialog ref="dialog" class="config-dialog" aria-labelledby="private-editor-title" @cancel.prevent="close">
      <template v-if="editorOpen"><div class="section-heading"><h2 id="private-editor-title">{{ editing ? '编辑私有词条' : '新增私有词条' }}</h2><button type="button" class="quiet" :disabled="saving" @click="close">关闭</button></div>
        <p class="note">不需要发布；发音留空会跳过音频生成。个人音频生成受后台配置控制。</p><p v-if="editorError" class="error" role="alert">{{ editorError }} <button v-if="editing" type="button" class="quiet" :disabled="saving" @click="reloadEditor">重新加载词条</button></p>
        <form @submit.prevent="save"><fieldset :disabled="saving || !online" class="editor-fields"><div class="form-grid">
          <label>语言<select v-model="language" :disabled="!!editing" required><option v-for="item in languages" :key="item.code" :value="item.code">{{ item.displayName }}</option></select></label>
          <label>书写系统<input v-model="script" :disabled="!!editing" pattern="[A-Z][a-z]{3}" maxlength="4" required></label>
        </div><label>单词写法<input v-model="written" maxlength="200" required></label>
        <ContentEditor v-model="content" personal /><div class="dialog-actions"><button type="button" class="quiet" @click="close">取消</button><button type="submit">{{ saving ? '正在保存…' : '保存私有词条' }}</button></div>
        </fieldset></form>
      </template>
    </dialog>
  </main></ion-content></ion-page>
</template>
