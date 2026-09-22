<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { IonPage, IonContent } from '@ionic/vue'
import { getJson, postJson } from '../shared/api'
import ContentEditor from '../features/dictionary/ContentEditor.vue'
import ContentView from '../features/dictionary/ContentView.vue'
import LanguageSettings from '../features/dictionary/LanguageSettings.vue'
import { emptyContent, statusLabel } from '../features/dictionary/types'
import type { AdminEntry, AdminLanguage, EntryRow, History, Results } from '../features/dictionary/types'

const tab = ref('dictionary')
const languages = ref<AdminLanguage[]>([])
const list = ref<Results<EntryRow>>({ items: [], total: 0, page: 0 })
const q = ref('')
const language = ref('')
const page = ref(0)
const busy = ref(false)
const error = ref('')
const message = ref('')
const editing = ref(false)
const entry = ref<AdminEntry | null>(null)
const identity = ref({ languageCode: 'ja', scriptCode: 'Jpan', written: '' })
const content = ref(emptyContent())
const note = ref('')
const saved = ref('')
const history = ref<Results<History>>({ items: [], total: 0, page: 0 })
const snapshot = () => JSON.stringify({ identity: identity.value, content: content.value })
const dirty = computed(() => editing.value && saved.value !== snapshot())
const banned = computed(() => entry.value?.status === 'BANNED')

/** 只在即将丢弃本地编辑时确认；正常保存和读取不打断流程。 */
function canLeave() { return !dirty.value || window.confirm('还有未保存的内容，确定放弃这些修改吗？') }
onBeforeRouteLeave(() => canLeave())
/** 浏览器刷新或关闭标签页时同样保护尚未保存的编辑内容。 */
function beforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

/** 分页读取后台词条和语言配置，允许按写法和语言筛选。 */
async function load() {
  const params = new URLSearchParams({ q: q.value, language: language.value, page: String(page.value) })
  list.value = await getJson('/api/v1/admin/dictionary?' + params)
  languages.value = await getJson('/api/v1/admin/languages')
}
/** 统一异步操作的禁用、错误和成功提示，避免重复提交。 */
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; message.value = ''
  try { await action() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '操作失败，请重试' }
  finally { busy.value = false }
}
/** 接收服务端最新版本，复制内容以免表单改写公开版预览。 */
function adopt(value: AdminEntry) {
  entry.value = value
  identity.value = { languageCode: value.languageCode, scriptCode: value.scriptCode, written: value.written }
  content.value = JSON.parse(JSON.stringify(value.draft ?? value.published ?? emptyContent()))
  editing.value = true
  saved.value = snapshot()
}
/** 新建表单尚不产生数据库记录。 */
function create() {
  if (!canLeave()) return
  entry.value = null; editing.value = true; content.value = emptyContent()
  identity.value = { languageCode: languages.value.find(l => l.code === 'ja')?.code ?? languages.value[0]?.code ?? '', scriptCode: 'Jpan', written: '' }
  history.value = { items: [], total: 0, page: 0 }; note.value = ''; message.value = ''; error.value = ''
  saved.value = snapshot()
}
/** 打开时先读取完整数据，读取失败不清空尚可查看的旧表单。 */
async function open(id: string) {
  if (!canLeave()) return
  await run(async () => {
    const value = await getJson<AdminEntry>('/api/v1/admin/dictionary/' + id)
    const versions = await getJson<Results<History>>('/api/v1/admin/dictionary/' + id + '/history')
    adopt(value); history.value = versions; note.value = ''
  })
}
/** 保存只产生草稿，不触发发布。 */
async function save() {
  await run(async () => {
    const value = entry.value
      ? await postJson<AdminEntry>('/api/v1/admin/dictionary/' + entry.value.id + '/draft', { version: entry.value.version, content: content.value })
      : await postJson<AdminEntry>('/api/v1/admin/dictionary', { ...identity.value, content: content.value })
    adopt(value); await load(); message.value = '草稿已保存，用户暂时看不到这些修改。'
  })
}
/** 发布、恢复和封禁都是带编辑版本的显式操作。 */
async function act(action: string) {
  if (!entry.value || dirty.value) return
  const prompt = action === 'publish' ? '确认发布当前草稿？发布后用户将看到此版本。'
    : action === 'ban' ? '确认封禁这个词条？用户将只能看到已封禁提示；当前暂不提供解除封禁。'
      : '将这个历史版本复制成草稿？公开内容会保持不变，直到再次发布。'
  if (!window.confirm(prompt)) return
  await run(async () => {
    const value = await postJson<AdminEntry>('/api/v1/admin/dictionary/' + entry.value!.id + '/' + action,
      { version: entry.value!.version, note: note.value })
    adopt(value); await load()
    history.value = await getJson('/api/v1/admin/dictionary/' + value.id + '/history')
    note.value = ''; message.value = action === 'publish' ? '已发布，用户词典已更新。' : action === 'ban' ? '词条已封禁。' : '历史内容已复制到草稿，请检查后发布。'
  })
}
/** 翻页保留已选中的编辑表单。 */
async function search(next = 0) { await run(async () => { page.value = next; await load() }) }
async function historyPage(next: number) {
  await run(async () => { history.value = await getJson('/api/v1/admin/dictionary/' + entry.value!.id + '/history?page=' + next) })
}
function switchTab(next: string) {
  if (!canLeave()) return
  editing.value = false; tab.value = next
  if (next === 'dictionary') void search()
}
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  void run(load)
})
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
<template>
  <ion-page><ion-content>
    <main class="workspace">
      <header class="account-bar"><router-link to="/">← 学习首页</router-link><router-link to="/dictionary">查看用户词典</router-link></header>
      <p class="brand">LANGUAGE LEAN · 后台管理</p><h1>词典工作台</h1>
      <p class="intro">整理词条、核对释义，让每次发布都有记录。</p>
      <nav class="tabs" aria-label="后台模块">
        <button :class="{ active: tab === 'dictionary' }" :disabled="busy" @click="switchTab('dictionary')">基础词典</button>
        <button :class="{ active: tab === 'languages' }" :disabled="busy" @click="switchTab('languages')">语言配置</button>
      </nav>
      <section v-if="tab === 'languages'"><LanguageSettings /></section>
      <template v-else>
        <p v-if="error" class="error feedback" role="alert">{{ error }} <button v-if="entry" class="quiet" :disabled="busy" @click="open(entry.id)">重新读取</button></p>
        <p v-if="message" class="feedback" role="status">{{ message }}</p>
        <div class="admin-grid">
          <section class="catalog-panel">
            <div class="section-heading"><h2>词条目录 <small>{{ list.total }}</small></h2><button :disabled="busy || !languages.length" @click="create">新增词条</button></div>
            <form class="search-form" @submit.prevent="search()">
              <label>写法搜索<input v-model="q" maxlength="200" placeholder="输入单词"></label>
              <label>语言<select v-model="language"><option value="">全部语言</option><option v-for="item in languages" :key="item.code" :value="item.code">{{ item.displayName }}</option></select></label>
              <button class="secondary" :disabled="busy">搜索</button>
            </form>
            <p v-if="!list.items.length" class="note">{{ busy ? '正在加载…' : '还没有匹配词条，可以先新增一个草稿。' }}</p>
            <div class="entry-list"><button v-for="item in list.items" :key="item.id" class="entry-row" :class="{ selected: entry?.id === item.id }" :disabled="busy" @click="open(item.id)">
              <strong>{{ item.written }}</strong><span>{{ item.languageCode }} · {{ statusLabel(item.status) }}<span v-if="item.hasDraft"> · 有草稿</span></span>
            </button></div>
            <div class="pagination"><button class="secondary" :disabled="busy || page === 0" @click="search(page - 1)">上一页</button><span>{{ page + 1 }}</span><button class="secondary" :disabled="busy || (page + 1) * 20 >= list.total" @click="search(page + 1)">下一页</button></div>
          </section>
          <section class="editor-panel">
            <template v-if="editing">
              <div class="section-heading"><h2>{{ entry ? entry.written : '新建词条' }}</h2><span class="tag">{{ entry ? statusLabel(entry.status) : '新草稿' }}</span></div>
              <p v-if="entry" class="note">公开版本：{{ entry.currentRevision || '尚未发布' }}<span v-if="dirty"> · 有未保存修改</span></p>
              <p v-if="banned" class="error">词条已封禁，内容仅供管理员查阅。</p>
              <form @submit.prevent="save">
                <fieldset :disabled="busy || banned">
                  <div class="form-grid">
                    <label>语言<select v-model="identity.languageCode" :disabled="!!entry" required><option v-for="item in languages" :key="item.code" :value="item.code">{{ item.displayName }}{{ item.enabled ? '' : '（已禁用）' }}</option></select></label>
                    <label>书写系统<select v-model="identity.scriptCode" :disabled="!!entry"><option value="Jpan">日语 · Jpan</option><option value="Latn">拉丁字母 · Latn</option><option value="Hans">简体汉字 · Hans</option><option value="Hant">繁体汉字 · Hant</option><option value="Kore">韩语 · Kore</option><option value="Cyrl">西里尔字母 · Cyrl</option><option value="Arab">阿拉伯字母 · Arab</option></select></label>
                  </div>
                  <label>单词写法<input v-model="identity.written" maxlength="200" required :disabled="!!entry" placeholder="例如：猫"></label>
                  <p class="note">创建后写法、语言和书写系统固定；内容可通过新版本修订。</p>
                  <ContentEditor v-model="content" />
                  <button type="submit" :disabled="!dirty && !!entry">保存草稿</button>
                </fieldset>
              </form>
              <div v-if="entry && !banned" class="publish-panel">
                <label>发布说明<input v-model="note" maxlength="500" :disabled="busy" placeholder="可填写本次修改原因"></label>
                <p class="note">先保存，再确认发布；至少填写一条释义。没有读音也能发布，听力资源后续补充。</p>
                <div class="actions"><button :disabled="busy || dirty || !entry.draft" @click="act('publish')">确认并发布</button>
                  <button class="secondary danger" :disabled="busy || dirty || !entry.currentRevision" @click="act('ban')">封禁词条</button>
                  <router-link v-if="entry.currentRevision" :to="'/dictionary/' + entry.id">用户视角</router-link></div>
              </div>
              <details v-if="entry?.published" class="history-card"><summary>当前公开内容 · 第 {{ entry.currentRevision }} 版</summary><ContentView :content="entry.published" /></details>
              <div v-if="entry" class="history-list">
                <h3>发布历史</h3><p v-if="!history.total" class="note">尚未发布，没有历史版本。</p>
                <details v-for="version in history.items" :key="version.revision" class="history-card">
                  <summary>第 {{ version.revision }} 版 · {{ new Date(version.publishedAt).toLocaleString() }}</summary>
                  <p class="note">发布人：{{ version.publishedBy }}</p><p>{{ version.note || '未填写发布说明' }}</p>
                  <ContentView :content="version.content" />
                  <button class="secondary" :disabled="busy || dirty || !!entry.draft || banned" @click="act('history/' + version.revision + '/restore')">复制为恢复草稿</button>
                </details>
                <p v-if="entry.draft && history.total" class="note">已有待发布草稿，先处理当前草稿后才能恢复历史。</p>
                <div v-if="history.total > 20" class="pagination"><button :disabled="busy || !history.page" @click="historyPage(history.page - 1)">上一页</button><span>{{ history.page + 1 }}</span><button :disabled="busy || (history.page + 1) * 20 >= history.total" @click="historyPage(history.page + 1)">下一页</button></div>
              </div>
            </template>
            <div v-else class="empty-state"><h2>从一个词条开始</h2><p>选择左侧词条查看内容，或新增词条。草稿保存后仍需确认发布。</p></div>
          </section>
        </div>
      </template>
    </main>
  </ion-content></ion-page>
</template>
