<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { IonPage, IonContent } from '@ionic/vue'
import { getJson } from '../shared/api'
import { useAuth } from '../features/auth/store'
import ContentView from '../features/dictionary/ContentView.vue'
import { statusLabel } from '../features/dictionary/types'
import type { EntryRow, PublicEntry, Results } from '../features/dictionary/types'
import type { Language } from '../features/languages/store'
const route = useRoute()
const auth = useAuth()
const q = ref('')
const language = ref('')
const page = ref(0)
const languages = ref<Language[]>([])
const results = ref<Results<EntryRow>>({ items: [], total: 0, page: 0 })
const detail = ref<PublicEntry | null>(null)
const busy = ref(false)
const error = ref('')
let requestId = 0
/** 路由和搜索都从服务端读取公开版；用请求序号防止较慢响应覆盖新页面。 */
async function load(next = 0) {
  const current = ++requestId
  busy.value = true; error.value = ''; detail.value = null
  try {
    if (route.params.id) {
      const value = await getJson<PublicEntry>('/api/v1/dictionary/' + encodeURIComponent(String(route.params.id)))
      if (current === requestId) detail.value = value
    } else {
      const params = new URLSearchParams({ q: q.value, language: language.value, page: String(next) })
      const [value, available] = await Promise.all([getJson<Results<EntryRow>>('/api/v1/dictionary?' + params), getJson<Language[]>('/api/v1/languages')])
      if (current === requestId) { results.value = value; languages.value = available; page.value = next }
    }
  } catch (cause) { if (current === requestId) error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { if (current === requestId) busy.value = false }
}
watch(() => route.params.id, () => load())
onMounted(() => load())
</script>
<template>
  <ion-page><ion-content><main>
    <header class="account-bar"><router-link to="/">← 学习首页</router-link><router-link v-if="auth.user?.roles.includes('ADMIN')" to="/admin">管理词典</router-link></header>
    <p class="brand">LANGUAGE LEAN · 基础词典</p>
    <p v-if="error" class="error" role="alert">{{ error }} <button class="secondary" @click="load(page)">重试</button></p>
    <p v-if="busy" role="status">正在加载词典…</p>
    <template v-if="route.params.id">
      <router-link to="/dictionary">← 返回词典目录</router-link>
      <section v-if="detail">
        <div class="section-heading"><h1 class="entry-title">{{ detail.written }}</h1><span class="tag">{{ statusLabel(detail.status) }}</span></div>
        <p class="note">{{ detail.languageCode }} · 第 {{ detail.currentRevision }} 版</p>
        <p v-if="detail.status === 'BANNED'" class="error">此词条已封禁，内容暂不可用。</p>
        <ContentView v-else-if="detail.content" :content="detail.content" />
      </section>
    </template>
    <template v-else>
      <h1>查一个词</h1><p class="intro">这里展示管理员已确认发布的基础词条。</p>
      <form class="search-form" @submit.prevent="load()">
        <label>单词写法<input v-model="q" maxlength="200" placeholder="输入要查询的单词"></label>
        <label>语言<select v-model="language"><option value="">全部语言</option><option v-for="item in languages" :key="item.code" :value="item.code">{{ item.displayName }}</option></select></label>
        <button :disabled="busy">查询</button>
      </form>
      <section>
        <h2>词条 <small>{{ results.total }}</small></h2>
        <p v-if="!results.items.length && !busy" class="note">没有匹配的已发布词条。管理员发布后即可在这里查看。</p>
        <div class="entry-list"><router-link v-for="item in results.items" :key="item.id" class="entry-row" :to="'/dictionary/' + item.id">
          <strong>{{ item.written }}</strong><span>{{ item.languageCode }} · {{ statusLabel(item.status) }}</span>
        </router-link></div>
        <div class="pagination"><button class="secondary" :disabled="busy || page === 0" @click="load(page - 1)">上一页</button><span>{{ page + 1 }}</span><button class="secondary" :disabled="busy || (page + 1) * 20 >= results.total" @click="load(page + 1)">下一页</button></div>
      </section>
    </template>
  </main></ion-content></ion-page>
</template>
