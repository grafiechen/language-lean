<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getJson, postJson } from '../../shared/api'
import type { AdminLanguage } from './types'
const items = ref<AdminLanguage[]>([])
const error = ref('')
const message = ref('')
const busy = ref(false)
const selected = ref<string | null>(null)
const form = ref({ code: '', displayName: '', pronunciationLocale: '', enabled: true, version: null as number | null })
/** 加载包含禁用项的后台配置。 */
async function load() {
  try { items.value = await getJson('/api/v1/admin/languages') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
}
/** 新增和编辑共享表单；既有语言的稳定代码不能改动。 */
function edit(item?: AdminLanguage) {
  selected.value = item?.code ?? null
  form.value = item ? { ...item } : { code: '', displayName: '', pronunciationLocale: '', enabled: true, version: null }
  error.value = ''; message.value = ''
}
/** 后端同时校验版本和区域格式，保存后刷新列表。 */
async function save() {
  busy.value = true; error.value = ''; message.value = ''
  try {
    const updated = await postJson<AdminLanguage>('/api/v1/admin/languages/' + encodeURIComponent(form.value.code), form.value)
    edit(updated); await load(); message.value = '语言配置已保存'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <div>
    <div class="section-heading"><h2>语言配置</h2><button class="secondary" :disabled="busy" @click="edit()">新增语言</button></div>
    <p class="note">禁用后，该语言的词条不再出现在用户词典中。新增语言默认启用听音回忆题型。</p>
    <div class="entry-list">
      <button v-for="item in items" :key="item.code" class="entry-row" :disabled="busy" @click="edit(item)">
        <strong>{{ item.displayName }}</strong><span>{{ item.code }} · {{ item.pronunciationLocale }} · {{ item.enabled ? '已启用' : '已禁用' }}</span>
      </button>
    </div>
    <form class="editor-fields" @submit.prevent="save">
      <fieldset :disabled="busy">
        <legend>{{ selected ? '编辑语言' : '新增语言' }}</legend>
        <div class="form-grid">
          <label>语言代码<input v-model="form.code" required maxlength="16" :disabled="!!selected" placeholder="例如：ja"></label>
          <label>显示名称<input v-model="form.displayName" required maxlength="80" placeholder="例如：日语"></label>
          <label>发音区域<input v-model="form.pronunciationLocale" required maxlength="35" placeholder="例如：ja-JP"></label>
          <label class="check-label"><input v-model="form.enabled" type="checkbox">启用此语言</label>
        </div>
        <button type="submit">保存配置</button>
      </fieldset>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
  </div>
</template>
