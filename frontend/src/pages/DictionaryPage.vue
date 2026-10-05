<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IonPage, IonContent } from '@ionic/vue'
import { getJson, postJson } from '../shared/api'
import { useAuth } from '../features/auth/store'
import ContentView from '../features/dictionary/ContentView.vue'
import { originLabel, statusLabel } from '../features/dictionary/types'
import type { EntryRow, PublicEntry, Results } from '../features/dictionary/types'
import type { Language } from '../features/languages/store'
import type { LearningItem, Wordbook } from '../features/learning/types'
import type { ContributionCredit } from '../features/dictionary/contributions'
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const q = ref('')
const language = ref('')
const page = ref(0)
const languages = ref<Language[]>([])
const results = ref<Results<EntryRow>>({ items: [], total: 0, page: 0 })
const detail = ref<PublicEntry | null>(null)
const credits = ref<ContributionCredit[]>([])
const busy = ref(false)
const error = ref('')
const wordbooks = ref<Wordbook[]>([])
const selectedWordbookId = ref('')
const adding = ref(false)
const addMessage = ref('')
const addError = ref('')
let requestId = 0
/** 列表位置随 URL 保存，详情刷新、新标签打开和浏览器后退均能恢复同一查询。 */
const listState = computed(() => {
  const text = (value: unknown) => typeof value === 'string' ? value : ''
  const candidate = Number(text(route.query.page))
  const currentPage = Number.isInteger(candidate) && candidate >= 0 && candidate <= 100000 ? candidate : 0
  return { q: text(route.query.q), language: text(route.query.language), page: currentPage }
})
const listLocation = computed(() => ({ path: '/dictionary', query: {
  ...(listState.value.q ? { q: listState.value.q } : {}),
  ...(listState.value.language ? { language: listState.value.language } : {}),
  ...(listState.value.page ? { page: String(listState.value.page) } : {}),
} }))
/** 查询重回第一页；翻页使用已提交条件，尚未点击查询的输入不改变当前列表。 */
async function showList(next = 0, search = false) {
  const target = { path: '/dictionary', query: {
    ...((search ? q.value : listState.value.q) ? { q: search ? q.value : listState.value.q } : {}),
    ...((search ? language.value : listState.value.language) ? { language: search ? language.value : listState.value.language } : {}),
    ...(next ? { page: String(next) } : {}),
  } }
  if (router.resolve(target).fullPath === route.fullPath) await load()
  else await router.push(target)
}
/** 路由和搜索都从服务端读取公开版；用请求序号防止较慢响应覆盖新页面。 */
async function load() {
  const current = ++requestId
  const state = { ...listState.value }
  q.value = state.q; language.value = state.language; page.value = state.page
  busy.value = true; error.value = ''; detail.value = null; credits.value = []
  try {
    if (route.params.id) {
      const value = await getJson<PublicEntry>('/api/v1/dictionary/' + encodeURIComponent(String(route.params.id)))
      const attribution = value.status === 'PUBLISHED' ? await getJson<ContributionCredit[]>('/api/v1/dictionary/' + value.id + '/contributions') : []
      if (current === requestId) {
        detail.value = value
        credits.value = attribution
        addMessage.value = ''; addError.value = ''
        if (value.status === 'PUBLISHED') await loadWordbooks()
      }
    } else {
      const params = new URLSearchParams({ q: state.q, language: state.language, page: String(state.page) })
      const [value, available] = await Promise.all([getJson<Results<EntryRow>>('/api/v1/dictionary?' + params), getJson<Language[]>('/api/v1/languages')])
      if (current === requestId) { results.value = value; languages.value = available }
    }
  } catch (cause) { if (current === requestId) error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { if (current === requestId) busy.value = false }
}
/** 详情页为已发布词条提供当前账户的单词本选择。 */
async function loadWordbooks() {
  try {
    wordbooks.value = await getJson<Wordbook[]>('/api/v1/learning/wordbooks')
    if (!wordbooks.value.some(book => book.id === selectedWordbookId.value)) selectedWordbookId.value = wordbooks.value[0]?.id ?? ''
  } catch (cause) { addError.value = cause instanceof Error ? cause.message : '单词本加载失败' }
}
/** 将当前公开词条加入所选单词本，服务端按账户和词条身份保证幂等。 */
async function addToWordbook() {
  if (!detail.value || !selectedWordbookId.value) return
  adding.value = true; addMessage.value = ''; addError.value = ''
  try {
    await postJson<LearningItem>('/api/v1/learning/wordbooks/' + selectedWordbookId.value + '/entries/' + detail.value.id)
    addMessage.value = '词条已加入当前单词本。'
    await loadWordbooks()
  } catch (cause) { addError.value = cause instanceof Error ? cause.message : '词条加入失败' }
  finally { adding.value = false }
}
watch(() => route.fullPath, () => {
  if (route.path === '/dictionary' || route.path.startsWith('/dictionary/')) void load()
  else requestId++
})
onMounted(() => load())
</script>
<template>
  <ion-page><ion-content><main>
    <header class="account-bar"><router-link to="/">← 学习首页</router-link><router-link v-if="auth.user?.roles.includes('ADMIN')" to="/admin">管理词典</router-link></header>
    <p class="brand">LANGUAGE LEAN · 基础词典</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="secondary" @click="load()">重试</button></p>
    <p v-if="busy" role="status">正在加载词典…</p>
    <template v-if="route.params.id">
      <router-link :to="listLocation">← 返回词典目录</router-link>
      <section v-if="detail">
        <div class="section-heading"><h1 class="entry-title">{{ detail.written }}</h1><span class="tag">{{ statusLabel(detail.status) }}</span></div>
        <p class="note">{{ detail.languageCode }} · 第 {{ detail.currentRevision }} 版 · {{ originLabel(detail.originType) }}</p>
        <p v-if="detail.status === 'BANNED'" class="error">此词条已封禁，内容暂不可用。</p>
        <ContentView v-else-if="detail.content" :content="detail.content" :entry-id="detail.id" />
        <div v-if="credits.length" class="subcard"><h3>已审核贡献来源</h3><p v-for="(credit, index) in credits" :key="index" class="note">第 {{ credit.revision }} 版 · {{ credit.sourceName }}<span v-if="credit.license"> · 许可：{{ credit.license }}</span><span v-if="credit.contributorDeleted"> · 已注销用户</span></p></div>
        <div v-if="detail.status === 'PUBLISHED'" class="subcard learning-add">
          <strong>加入我的单词本</strong>
          <p v-if="addError" class="error" role="alert">{{ addError }}</p>
          <p v-if="addMessage" class="feedback" role="status">{{ addMessage }}</p>
          <template v-if="wordbooks.length">
            <label>选择单词本<select v-model="selectedWordbookId"><option v-for="book in wordbooks" :key="book.id" :value="book.id">{{ book.name }}（{{ book.itemCount }}）</option></select></label>
            <button :disabled="adding" @click="addToWordbook">加入</button>
          </template>
          <p v-else class="note">还没有单词本，先去<a href="/learning">我的单词本</a>创建一个。</p>
        </div>
      </section>
    </template>
    <template v-else>
      <h1>查一个词</h1><p class="intro">这里展示管理员已确认发布的基础词条。</p>
      <form class="search-form" @submit.prevent="showList(0, true)">
        <label>单词写法或读音<input v-model="q" maxlength="200" placeholder="输入单词或假名，例如：いじめる"></label>
        <label>语言<select v-model="language"><option value="">全部语言</option><option v-for="item in languages" :key="item.code" :value="item.code">{{ item.displayName }}</option></select></label>
        <button :disabled="busy">查询</button>
      </form>
      <section>
        <h2>词条 <small>{{ results.total }}</small></h2>
        <p v-if="!results.items.length && !busy" class="note">没有匹配的已发布词条。管理员发布后即可在这里查看。</p>
        <div class="entry-list"><router-link v-for="item in results.items" :key="item.id" class="entry-row" :to="{ path: '/dictionary/' + item.id, query: listLocation.query }">
          <strong>{{ item.written }}</strong><span>{{ item.languageCode }} · {{ statusLabel(item.status) }}</span>
        </router-link></div>
        <div class="pagination"><button class="secondary" :disabled="busy || page === 0" @click="showList(page - 1)">上一页</button><span>{{ page + 1 }}</span><button class="secondary" :disabled="busy || (page + 1) * 20 >= results.total" @click="showList(page + 1)">下一页</button></div>
      </section>
    </template>
  </main></ion-content></ion-page>
</template>
