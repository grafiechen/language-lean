<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuth } from '../auth/store'
import { accountKey } from '../../platform/web/database'
import { learningDatabase } from '../../platform/web/reviewSync'
import { audioRequestKey, cacheReadyAudio, ensureAudio, pronunciationHash } from '../../platform/web/audio'
import type { AudioRequest } from './types'
import { onBeforeRouteLeave } from 'vue-router'
import AudioFeedbackDialog from './AudioFeedbackDialog.vue'

const props = withDefaults(defineProps<{ request: AudioRequest; label?: string; auto?: boolean; feedback?: boolean; allowRegenerate?: boolean }>(), { label: '播放发音', auto: true, feedback: true, allowRegenerate: true })
const auth = useAuth()
const busy = ref(false), message = ref('')
const audioVersionId = ref<string | null>(null)
let player: HTMLAudioElement | null = null, objectUrl = '', disposed = false
const owner = computed(() => auth.user ? { serverId: window.location.origin, userId: auth.user.id } : null)
/** 点击和详情补齐共用入口；离线只按当前内容哈希读取当前账号缓存。 */
async function prepare(play: boolean, force = false) {
  if (busy.value || !owner.value) return
  const currentOwner = { ...owner.value }, request = { ...props.request }
  busy.value = true; message.value = ''
  try {
    let blob: Blob | undefined
    if (!navigator.onLine && !force) {
      const hash = await pronunciationHash(request.pronunciationText)
      const cached = await learningDatabase.audio.where('accountKey').equals(accountKey(currentOwner))
        .filter(row => row.textHash === hash && row.requestKey === audioRequestKey(request)).toArray()
      cached.sort((a, b) => b.cachedAt.localeCompare(a.cachedAt))
      blob = cached[0]?.blob
      audioVersionId.value = cached[0]?.audioVersionId ?? null
      if (!blob) throw new Error('此发音尚未缓存，请联网补齐。')
    } else {
      const result = await ensureAudio(currentOwner, request, force)
      if (disposed || owner.value?.userId !== currentOwner.userId) return
      audioVersionId.value = result.audioVersionId
      message.value = result.message
      if (result.status !== 'READY') return
      blob = await cacheReadyAudio(currentOwner, request, result)
    }
    if (play && blob && !disposed && owner.value?.userId === currentOwner.userId) {
      stop(); objectUrl = URL.createObjectURL(blob); player = new Audio(objectUrl)
      player.onerror = () => { message.value = '音频无法播放，请联网重新生成。' }
      await player.play()
    }
  } catch (cause) { if (!disposed) message.value = cause instanceof Error ? cause.message : '发音准备失败，请重试。' }
  finally { if (!disposed) busy.value = false }
}
/** 退出页面时停止音频并释放 Blob URL。 */
function stop() { player?.pause(); player = null; if (objectUrl) URL.revokeObjectURL(objectUrl); objectUrl = '' }
onMounted(() => { if (props.auto && props.request.pronunciationText.trim()) void prepare(false) })
onBeforeUnmount(() => { disposed = true; stop() })
onBeforeRouteLeave(() => stop())
</script>
<template>
  <div class="audio-control">
    <button type="button" class="quiet" :disabled="busy || !request.pronunciationText.trim()" @click="prepare(true)">{{ busy ? '准备音频…' : label }}</button>
    <button v-if="allowRegenerate && (['PERSONAL', 'OVERRIDE'].includes(request.scope) || auth.user?.roles.includes('ADMIN')) && request.pronunciationText.trim()" type="button" class="quiet" :disabled="busy" @click="prepare(true, true)">重新生成发音</button>
    <AudioFeedbackDialog v-if="feedback && request.scope === 'PUBLISHED' && request.pronunciationText.trim()" :request="request" :audio-version-id="audioVersionId" />
    <small v-if="!request.pronunciationText.trim()" class="note">未填写发音，不生成音频。</small>
    <small v-else-if="message" class="note" role="status">{{ message }}</small>
  </div>
</template>
