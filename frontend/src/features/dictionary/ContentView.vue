<script setup lang="ts">
import type { DictionaryContent } from './types'
import AudioButton from '../audio/AudioButton.vue'
import type { AudioScope } from '../audio/types'
import { useAuth } from '../auth/store'
import { languageName, localize } from './translations'
import TranslationCredit from './TranslationCredit.vue'
const auth = useAuth()
/** 所有在线和离线详情复用同一个译文选择规则，绝不改变发音请求。 */
function translated(text: string, language: string | undefined, translations: DictionaryContent['senses'][number]['translations'], source: string) {
  return localize(text, language, translations, auth.user?.nativeLanguage ?? 'zh-Hans', source)
}
withDefaults(defineProps<{ content: DictionaryContent; entryId?: string; audioScope?: AudioScope;
  wordAudio?: { entryId: string; scope: AudioScope; audioRevision?: number }; exampleAudio?: { entryId: string; scope: AudioScope; audioRevision?: number } }>(), { audioScope: 'PUBLISHED' })
</script>
<template>
  <div class="entry-content">
    <div v-if="content.readings.length" class="readings">
      <div v-for="(reading, index) in content.readings" :key="reading.id" class="tag">
        {{ reading.reading }}<small v-if="index === 0"> · 主要读音</small>
        <AudioButton v-if="entryId || wordAudio" :key="JSON.stringify([wordAudio, reading])" :request="{ entryId: wordAudio?.entryId ?? entryId!, resourceId: reading.id, kind: 'WORD', scope: wordAudio?.scope ?? audioScope, audioRevision: wordAudio?.audioRevision, pronunciationText: reading.pronunciationText }" />
      </div>
    </div>
    <p v-else class="note">尚未填写读音。</p>
    <article v-for="(sense, index) in content.senses" :key="sense.id" class="sense">
      <p class="note">{{ index + 1 }}. {{ sense.partOfSpeech || '未标注词性' }}</p>
      <p class="preserve-lines">{{ translated(sense.gloss, sense.glossLanguage, sense.translations, content.sourceName).text }}</p>
      <TranslationCredit :credit="translated(sense.gloss, sense.glossLanguage, sense.translations, content.sourceName).attribution" />
      <small v-if="translated(sense.gloss, sense.glossLanguage, sense.translations, content.sourceName).fallback" class="note">暂无所选母语译文，显示{{ languageName(translated(sense.gloss, sense.glossLanguage, sense.translations, content.sourceName).language) }}释义。</small>
      <blockquote v-for="example in sense.examples" :key="example.id">
        <p>{{ example.text }}</p><p v-if="example.pronunciationText && example.pronunciationText !== example.text" class="note">{{ example.pronunciationText }}</p>
        <p v-if="translated(example.translation, example.translationLanguage, example.translations, content.sourceName).text">{{ translated(example.translation, example.translationLanguage, example.translations, content.sourceName).text }}</p>
        <TranslationCredit :credit="translated(example.translation, example.translationLanguage, example.translations, content.sourceName).attribution" />
        <small v-if="translated(example.translation, example.translationLanguage, example.translations, content.sourceName).fallback" class="note">暂无所选母语译文，显示{{ languageName(translated(example.translation, example.translationLanguage, example.translations, content.sourceName).language) }}译文。</small>
        <p v-if="example.attribution" class="note">例句来源：<a v-if="example.attribution.sourceUrl?.startsWith('https://')" :href="example.attribution.sourceUrl" target="_blank" rel="noreferrer">{{ example.attribution.sourceName }}</a><span v-else>{{ example.attribution.sourceName }}</span> · {{ example.attribution.license }}<span v-if="example.attribution.author"> · {{ example.attribution.author }}</span></p>
        <AudioButton v-if="entryId || exampleAudio" :key="JSON.stringify([exampleAudio, example])" label="播放例句" :request="{ entryId: exampleAudio?.entryId ?? entryId!, resourceId: example.id, kind: 'EXAMPLE', scope: exampleAudio?.scope ?? audioScope, audioRevision: exampleAudio?.audioRevision, pronunciationText: example.pronunciationText }" />
      </blockquote>
    </article>
    <p v-if="content.sourceName || content.license" class="note">来源：
      <a v-if="content.sourceName.includes('JMdict')" href="https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html" target="_blank" rel="noreferrer">{{ content.sourceName }}</a>
      <span v-else>{{ content.sourceName || '未注明' }}</span>
      <span v-if="content.license"> · 许可：<a v-if="content.license === 'CC BY-SA 4.0'" href="https://www.edrdg.org/edrdg/licence.html" target="_blank" rel="noreferrer">{{ content.license }}</a><span v-else>{{ content.license }}</span></span>
    </p>
  </div>
</template>
