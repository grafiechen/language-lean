import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getJson } from '../../shared/api'

/** 后端返回的语言配置及该语言启用的题型版本。 */
export interface Language {
  code: string
  displayName: string
  pronunciationLocale: string
  reviewTypes: { typeId: string; contractVersion: number }[]
}
/** 缓存语言配置并提供统一刷新入口。 */
export const useLanguages = defineStore('languages', () => {
  const items = ref<Language[]>([])
  const error = ref('')
  const loading = ref(false)
  /** 从服务端重新加载当前可用语言。 */
  async function refresh() {
    loading.value = true
    error.value = ''
    try { items.value = await getJson<Language[]>('/api/v1/languages') }
    catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
    finally { loading.value = false }
  }
  return { items, error, loading, refresh }
})
