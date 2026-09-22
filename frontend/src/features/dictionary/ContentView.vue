<script setup lang="ts">
import type { DictionaryContent } from './types'
defineProps<{ content: DictionaryContent }>()
</script>
<template>
  <div class="entry-content">
    <div v-if="content.readings.length" class="readings">
      <span v-for="(reading, index) in content.readings" :key="reading.id" class="tag">
        {{ reading.reading }}<small v-if="index === 0"> · 主要读音</small>
      </span>
    </div>
    <p v-else class="note">尚未填写读音。</p>
    <article v-for="(sense, index) in content.senses" :key="sense.id" class="sense">
      <p class="note">{{ index + 1 }}. {{ sense.partOfSpeech || '未标注词性' }}</p>
      <p class="preserve-lines">{{ sense.gloss }}</p>
      <blockquote v-for="example in sense.examples" :key="example.id">
        <p>{{ example.text }}</p><p v-if="example.pronunciationText" class="note">{{ example.pronunciationText }}</p>
        <p v-if="example.translation">{{ example.translation }}</p>
      </blockquote>
    </article>
    <p v-if="content.sourceName || content.license" class="note">来源：{{ content.sourceName || '未注明' }}<span v-if="content.license"> · 许可：{{ content.license }}</span></p>
  </div>
</template>
