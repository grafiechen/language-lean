<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getJson } from '../../shared/api'
import type { DictionaryContent, SystemDictionary } from './types'
import TranslationsEditor from './TranslationsEditor.vue'
import { nativeLanguages } from './translations'
const content = defineModel<DictionaryContent>({ required: true })
const props = withDefaults(defineProps<{ personal?: boolean; editReadings?: boolean; editSenses?: boolean; editAttribution?: boolean }>(),
  { personal: false, editReadings: true, editSenses: true, editAttribution: true })
const sourceDictionary = ref<SystemDictionary | null>(null)
const sourceError = ref('')
/** 启用选项用于新编辑；当前历史值即使已停用也保留在下拉框中。 */
const sourceOptions = computed(() => {
  const options = (sourceDictionary.value?.enabled ? sourceDictionary.value.items.filter(item => item.enabled) : [])
    .map(item => ({ value: item.value, label: item.displayName }))
  if (content.value.sourceName && !options.some(item => item.value === content.value.sourceName))
    options.unshift({ value: content.value.sourceName, label: content.value.sourceName + '（历史值）' })
  return options
})
/** 编辑器只读取配置；维护操作集中在后台“系统字典”页。 */
async function loadSources() {
  try { sourceDictionary.value = await getJson((props.personal ? '/api/v1/system-dictionaries/' : '/api/v1/admin/system-dictionaries/') + 'CONTENT_SOURCE') }
  catch (cause) { sourceError.value = cause instanceof Error ? cause.message : '内容来源加载失败' }
}
/** 新增子项只生成一次身份，之后编辑文本和发布沿用同一个 ID。 */
function addReading() {
  content.value.readings.push({ id: crypto.randomUUID(), reading: '', pronunciationText: '' })
}
function addSense() {
  content.value.senses.push({ id: crypto.randomUUID(), partOfSpeech: '', gloss: '', examples: [], glossLanguage: 'zh-Hans', translations: {} })
}
function addExample(index: number) {
  content.value.senses[index]!.examples.push({ id: crypto.randomUUID(), text: '', pronunciationText: '', translation: '', translationLanguage: 'zh-Hans', translations: {} })
}
onMounted(() => { if (props.editAttribution) void loadSources() })
</script>
<template>
  <div class="editor-fields">
    <template v-if="editReadings">
    <div class="section-heading"><h3>读音</h3><button type="button" class="secondary" :disabled="content.readings.length >= 20" @click="addReading">添加读音</button></div>
    <p class="note">第一项为主要读音。发音文本留空时，后续不会自动生成语音。</p>
    <div v-for="(reading, index) in content.readings" :key="reading.id" class="subcard">
      <div class="form-grid">
        <label>读音 / 假名<input v-model="reading.reading" maxlength="200" placeholder="例如：ねこ"></label>
        <label>发音文本<input v-model="reading.pronunciationText" maxlength="500" placeholder="填写语音应朗读的内容"></label>
      </div>
      <div class="actions"><button v-if="index > 0" type="button" class="quiet" @click="content.readings.unshift(...content.readings.splice(index, 1))">设为主要</button>
        <button type="button" class="quiet danger" @click="content.readings.splice(index, 1)">移除读音</button></div>
    </div>
    </template><template v-if="editSenses">
    <div class="section-heading"><h3>词义与例句</h3><button type="button" class="secondary" :disabled="content.senses.length >= 50" @click="addSense">添加词义</button></div>
    <div v-for="(sense, index) in content.senses" :key="sense.id" class="subcard">
      <div class="section-heading"><strong>词义 {{ index + 1 }}</strong><div class="actions">
        <button v-if="index > 0" type="button" class="quiet" @click="content.senses.unshift(...content.senses.splice(index, 1))">设为主要</button>
        <button type="button" class="quiet danger" @click="content.senses.splice(index, 1)">移除词义</button></div></div>
      <label>词性<input v-model="sense.partOfSpeech" maxlength="100" placeholder="例如：名词"></label>
      <label>释义<textarea v-model="sense.gloss" maxlength="4000" rows="3" :placeholder="personal ? '可后续补充' : '发布时必填'"></textarea></label>
      <label>释义语言<select v-model="sense.glossLanguage"><option value="">未标注语言（历史内容）</option><option v-for="option in nativeLanguages" :key="option.code" :value="option.code">{{ option.name }}</option></select></label>
      <TranslationsEditor v-model="sense.translations" :max-length="4000" />
      <div v-for="(example, exampleIndex) in sense.examples" :key="example.id" class="example-fields">
        <label>例句<input v-model="example.text" maxlength="2000"></label>
        <label>例句发音文本<input v-model="example.pronunciationText" maxlength="2000" placeholder="可留空"></label>
        <label>例句译文<input v-model="example.translation" maxlength="2000"></label>
        <label>例句译文语言<select v-model="example.translationLanguage"><option value="">未标注语言（历史内容）</option><option v-for="option in nativeLanguages" :key="option.code" :value="option.code">{{ option.name }}</option></select></label>
        <TranslationsEditor v-model="example.translations" :max-length="2000" />
        <p v-if="example.attribution" class="field-note">原例句来源：{{ example.attribution.sourceName }} · {{ example.attribution.license }}。修改外部例句时请同步核对来源。</p>
        <button type="button" class="quiet danger" @click="sense.examples.splice(exampleIndex, 1)">移除例句</button>
      </div>
      <button type="button" class="secondary" :disabled="sense.examples.length >= 20" @click="addExample(index)">添加例句</button>
    </div>
    </template><div v-if="editAttribution" class="form-grid"><label>内容来源
        <select v-model="content.sourceName" required>
          <option value="" disabled>{{ sourceError || '请选择内容来源' }}</option>
          <option v-for="option in sourceOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
        </select>
        <small class="field-note">选项可在后台“系统字典”中的“内容来源”字典维护。</small>
      </label>
      <label>内容许可<input v-model="content.license" maxlength="200" placeholder="可留空，例如 CC BY-SA 4.0">
        <small class="field-note">记录内容允许如何使用、修改或再发布，例如 CC BY-SA 4.0、CC0。原创手工内容可留空。</small>
      </label></div>
  </div>
</template>
