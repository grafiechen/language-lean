<script setup lang="ts">
import type { Translation } from './types'
import { nativeLanguages, languageName } from './translations'
import { ref } from 'vue'
const values = defineModel<Record<string, Translation> | undefined>()
const props = defineProps<{ maxLength: number }>()
const language = ref('en')
/** 译文不会覆盖原释义；同一语言只添加一次，来源随内容存入版本。 */
function add() {
  if (values.value?.[language.value]) return
  values.value = { ...values.value, [language.value]: { text: '', sourceName: '手工录入', license: '', sourceUrl: '' } }
}
function remove(code: string) { const next = { ...values.value }; delete next[code]; values.value = next }
</script>
<template>
  <div class="translations-editor">
    <div class="actions"><label>追加译文语言<select v-model="language"><option v-for="option in nativeLanguages" :key="option.code" :value="option.code">{{ option.name }}</option></select></label><button type="button" class="secondary" :disabled="!!values?.[language]" @click="add">添加译文</button></div>
    <div v-for="(translation, code) in values" :key="code" class="subcard">
      <label>{{ languageName(String(code)) }}译文<textarea v-model="translation.text" :maxlength="props.maxLength" rows="2"></textarea></label>
      <div class="form-grid"><label>译文来源<input v-model="translation.sourceName" maxlength="200" required></label><label>译文许可<input v-model="translation.license" maxlength="200" placeholder="原创可留空"></label></div>
      <label>出处链接<input v-model="translation.sourceUrl" maxlength="1000" placeholder="可留空，填写 HTTPS 地址"></label>
      <label>译文作者<input v-model="translation.author" maxlength="200" placeholder="可留空，外部内容保留原作者"></label>
      <button type="button" class="quiet danger" @click="remove(String(code))">移除此译文</button>
    </div>
  </div>
</template>
