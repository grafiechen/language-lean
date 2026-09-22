<script setup lang="ts">
import type { DictionaryContent } from './types'
const content = defineModel<DictionaryContent>({ required: true })
/** 新增子项只生成一次身份，之后编辑文本和发布沿用同一个 ID。 */
function addReading() {
  content.value.readings.push({ id: crypto.randomUUID(), reading: '', pronunciationText: '' })
}
function addSense() {
  content.value.senses.push({ id: crypto.randomUUID(), partOfSpeech: '', gloss: '', examples: [] })
}
function addExample(index: number) {
  content.value.senses[index]!.examples.push({ id: crypto.randomUUID(), text: '', pronunciationText: '', translation: '' })
}
</script>
<template>
  <div class="editor-fields">
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
    <div class="section-heading"><h3>词义与例句</h3><button type="button" class="secondary" :disabled="content.senses.length >= 50" @click="addSense">添加词义</button></div>
    <div v-for="(sense, index) in content.senses" :key="sense.id" class="subcard">
      <div class="section-heading"><strong>词义 {{ index + 1 }}</strong><div class="actions">
        <button v-if="index > 0" type="button" class="quiet" @click="content.senses.unshift(...content.senses.splice(index, 1))">设为主要</button>
        <button type="button" class="quiet danger" @click="content.senses.splice(index, 1)">移除词义</button></div></div>
      <label>词性<input v-model="sense.partOfSpeech" maxlength="100" placeholder="例如：名词"></label>
      <label>中文释义<textarea v-model="sense.gloss" maxlength="4000" rows="3" placeholder="发布时必填"></textarea></label>
      <div v-for="(example, exampleIndex) in sense.examples" :key="example.id" class="example-fields">
        <label>例句<input v-model="example.text" maxlength="2000"></label>
        <label>例句发音文本<input v-model="example.pronunciationText" maxlength="2000" placeholder="可留空"></label>
        <label>例句译文<input v-model="example.translation" maxlength="2000"></label>
        <button type="button" class="quiet danger" @click="sense.examples.splice(exampleIndex, 1)">移除例句</button>
      </div>
      <button type="button" class="secondary" :disabled="sense.examples.length >= 20" @click="addExample(index)">添加例句</button>
    </div>
    <div class="form-grid"><label>内容来源<input v-model="content.sourceName" maxlength="200" placeholder="手工录入或词典名称"></label>
      <label>内容许可<input v-model="content.license" maxlength="200" placeholder="可留空"></label></div>
  </div>
</template>
