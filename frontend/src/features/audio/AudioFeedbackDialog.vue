<script setup lang="ts">
import { onBeforeUnmount, ref, useId, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { onIonViewWillLeave } from '@ionic/vue'
import { useAuth } from '../auth/store'
import { currentReviewScope } from '../../platform/web/reviewSync'
import { networkOnline as online } from '../../platform/web/connectivity'
import { feedbackCategory, feedbackStatus, feedbackSubmission, ownFeedback, submitFeedback, type AudioFeedback, type FeedbackSubmission } from './feedback'
import type { AudioRequest } from './types'
import type { Results } from '../dictionary/types'

const props = defineProps<{ request: AudioRequest; audioVersionId: string | null }>()
const auth = useAuth(), dialog = ref<HTMLDialogElement | null>(null)
const titleId = useId()
const busy = ref(false), error = ref(''), message = ref(''), description = ref('')
const category = ref<AudioFeedback['category']>('WRONG_PRONUNCIATION')
const rows = ref<Results<AudioFeedback>>({ items: [], total: 0, page: 0 })
let owner = '', sequence = 0, captured: AudioRequest | null = null, capturedVersion: string | null = null, submission: FeedbackSubmission | undefined
/** 固定打开窗口时的公共资源和账号；音频播放刷新不能悄悄改变反馈版本。 */
function open() {
  if (!online.value || !auth.serverAuthenticated || !auth.user || props.request.scope !== 'PUBLISHED') return
  owner = auth.user.id; captured = { ...props.request }; capturedVersion = props.audioVersionId; submission = undefined
  description.value = ''; category.value = 'WRONG_PRONUNCIATION'; error.value = message.value = ''
  rows.value = { items: [], total: 0, page: 0 }; dialog.value?.showModal(); void load()
}
/** 会话变化或页面离开后丢弃旧响应，避免把上一账号结果展示给新账号。 */
async function run(action: (valid: () => boolean) => Promise<void>) {
  if (busy.value || !captured || !owner) return
  const ticket = ++sequence, account = owner
  const valid = () => ticket === sequence && owner === account && auth.user?.id === account && online.value && auth.serverAuthenticated
  busy.value = true; error.value = ''
  try {
    if (!valid() || (await currentReviewScope()).userId !== account) throw new Error('请联网登录原账号后重新打开反馈。')
    await action(valid)
  } catch (cause) { if (valid()) error.value = cause instanceof Error ? cause.message : '反馈操作失败。' }
  finally { if (valid()) busy.value = false }
}
async function load(page = 0) {
  await run(async valid => { const result = await ownFeedback(captured!, page); if (valid() && (await currentReviewScope()).userId === owner) rows.value = result })
}
/** 失败时保留相同 UUID 与输入，用户再次点击不会重复提交；成功后清空表单。 */
async function submit() {
  await run(async valid => {
    submission = feedbackSubmission(captured!, category.value, description.value, capturedVersion, submission)
    await submitFeedback(owner, submission)
    if (!valid()) return
    message.value = '已提交，管理员处理说明会显示在下方。'; description.value = ''; submission = undefined
    const result = await ownFeedback(captured!); if (valid() && (await currentReviewScope()).userId === owner) rows.value = result
  })
}
function suspend() { sequence++; busy.value = false; owner = ''; captured = null; submission = undefined; dialog.value?.close(); rows.value = { items: [], total: 0, page: 0 }; description.value = ''; error.value = message.value = '' }
watch(() => [auth.user?.id, auth.serverAuthenticated, online.value, props.request.entryId, props.request.resourceId, props.request.scope, props.request.pronunciationText], suspend)
onBeforeRouteLeave(suspend); onIonViewWillLeave(suspend); onBeforeUnmount(suspend)
</script>
<template>
  <button type="button" class="quiet" :disabled="!online || !auth.serverAuthenticated" @click="open">报告发音问题</button>
  <dialog ref="dialog" class="config-dialog" :aria-labelledby="titleId" @cancel="busy && $event.preventDefault()">
    <h2 :id="titleId">公共发音问题</h2>
    <p class="note">{{ request.kind === 'WORD' ? '词条' : '例句' }}发音：{{ request.pronunciationText }}。只提交这里的公共发音和你填写的说明。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <form @submit.prevent="submit"><fieldset :disabled="busy">
      <label>问题类型<select v-model="category"><option value="WRONG_PRONUNCIATION">读音不正确</option><option value="UNPLAYABLE">无法播放或音频异常</option></select></label>
      <label>问题说明<textarea v-model="description" maxlength="1000" required rows="3" placeholder="例如读错的音节、建议读音或播放时遇到的问题"></textarea></label>
      <div class="actions"><button type="submit">提交反馈</button><button type="button" class="secondary" @click="suspend">关闭</button></div>
    </fieldset></form>
    <div class="section-heading"><h3>我对这个发音的反馈</h3><button type="button" class="quiet" :disabled="busy" @click="load(rows.page)">刷新</button></div>
    <p v-if="!rows.total" class="note">尚无反馈记录。</p>
    <ul class="entry-list"><li v-for="row in rows.items" :key="row.id" class="entry-row"><strong>{{ feedbackCategory(row.category) }} · {{ feedbackStatus(row.status) }}</strong><span class="preserve-lines">{{ row.description }}</span><span v-if="row.resolutionNote" class="preserve-lines">处理说明：{{ row.resolutionNote }}</span><small>{{ new Date(row.createdAt).toLocaleString() }}</small></li></ul>
    <div v-if="rows.total > 20" class="pagination"><button type="button" :disabled="busy || rows.page === 0" @click="load(rows.page - 1)">上一页</button><span>{{ rows.page + 1 }}</span><button type="button" :disabled="busy || (rows.page + 1) * 20 >= rows.total" @click="load(rows.page + 1)">下一页</button></div>
  </dialog>
</template>
