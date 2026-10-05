<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuth } from '../auth/store'
import { currentReviewScope } from '../../platform/web/reviewSync'
import { getJson, postJson } from '../../shared/api'
import { feedbackCategory, feedbackStatus, type AudioFeedback, type AudioFeedbackDetail } from './feedback'
import type { AudioResult } from './types'
import type { Results } from '../dictionary/types'
import AudioButton from './AudioButton.vue'

const emit = defineEmits<{ edit: [entryId: string] }>()
const auth = useAuth(), status = ref('PENDING'), busy = ref(false), error = ref(''), message = ref('')
const rows = ref<Results<AudioFeedback>>({ items: [], total: 0, page: 0 })
const selected = ref<AudioFeedbackDetail | null>(null), note = ref(''), dialog = ref<HTMLDialogElement | null>(null)
const base = '/api/v1/admin/audio-feedback'
let sequence = 0
/** 写操作绑定打开表单的管理员账号，并在异步响应后再次核对。 */
async function run(action: (valid: () => boolean, owner: string) => Promise<void>) {
  if (busy.value) return
  const ticket = ++sequence, owner = auth.user?.id
  const valid = () => ticket === sequence && owner === auth.user?.id && auth.serverAuthenticated
  busy.value = true; error.value = ''
  try {
    if (!owner || !valid() || (await currentReviewScope()).userId !== owner) throw new Error('请重新登录管理员账号。')
    await action(valid, owner)
  } catch (cause) { if (valid()) error.value = cause instanceof Error ? cause.message : '处理失败。' }
  finally { if (valid()) busy.value = false }
}
/** 按状态查询20条反馈，关闭后的处理结果保留在对应筛选中。 */
async function load(page = 0) {
  await run(async valid => { const value = await getJson<Results<AudioFeedback>>(base + '?' + new URLSearchParams({ status: status.value, page: String(page) })); if (valid() && (await currentReviewScope()).userId === auth.user?.id) rows.value = value })
}
async function open(id: string) {
  await run(async valid => { const value = await getJson<AudioFeedbackDetail>(base + '/' + id); if (valid() && (await currentReviewScope()).userId === auth.user?.id) { selected.value = value; note.value = ''; message.value = ''; dialog.value?.showModal() } })
}
/** 生成成功仍需试听确认，不自动关闭反馈；失败和未配置会保持待处理。 */
async function regenerate() {
  if (!selected.value) return
  await run(async (valid, owner) => {
    const current = selected.value!
    const result = await postJson<AudioResult>(base + '/' + current.report.id + '/regenerate', { version: current.report.version }, { 'X-Learning-Account': owner })
    if (!valid()) return
    message.value = result.status === 'READY' ? '新音频已生成，请试听确认，再填写处理说明。' : result.message
    const fresh = await getJson<AudioFeedbackDetail>(base + '/' + current.report.id); if (valid()) selected.value = fresh
  })
}
/** 处理说明会向提交者展示；旧版本冲突时提供重新读取。 */
async function resolve(result: 'RESOLVED' | 'DISMISSED') {
  if (!selected.value || !note.value.trim()) { error.value = '请填写处理说明。'; return }
  await run(async (valid, owner) => {
    const current = selected.value!
    const value = await postJson<AudioFeedback>(base + '/' + current.report.id + '/resolve', { version: current.report.version, status: result, note: note.value }, { 'X-Learning-Account': owner })
    if (!valid()) return
    selected.value = { ...current, report: value }; message.value = '处理结果已保存，用户可以查看说明。'
    const fresh = await getJson<Results<AudioFeedback>>(base + '?' + new URLSearchParams({ status: status.value, page: String(rows.value.page) })); if (valid()) rows.value = fresh
  })
}
function edit() { const id = selected.value?.report.entryId; suspend(); if (id) emit('edit', id) }
/** 后台离开、切换标签或账号后关闭弹窗，不保留旧管理员的编辑状态。 */
function suspend() { sequence++; busy.value = false; selected.value = null; dialog.value?.close(); error.value = message.value = ''; note.value = '' }
watch(() => auth.user?.id, () => { suspend(); rows.value = { items: [], total: 0, page: 0 }; void load() })
onMounted(() => load()); onBeforeUnmount(suspend)
defineExpose({ load, suspend })
</script>
<template>
  <section><div class="section-heading"><h2>发音反馈</h2><button class="secondary" :disabled="busy" @click="load(rows.page)">刷新列表</button></div>
    <p class="note">核对公共发音，必要时先修改词典并发布，再生成新音频、试听和填写处理说明。</p>
    <p v-if="error && !selected" class="error" role="alert">{{ error }}</p>
    <label>处理状态<select v-model="status" :disabled="busy" @change="load()"><option value="PENDING">待处理</option><option value="RESOLVED">已修复</option><option value="DISMISSED">无需修复</option><option value="">全部</option></select></label>
    <p v-if="!rows.total" class="note">暂无反馈。</p>
    <ul class="entry-list"><li v-for="row in rows.items" :key="row.id"><button class="entry-row" :disabled="busy" @click="open(row.id)"><strong>{{ row.kind === 'WORD' ? '词条' : '例句' }} · {{ row.pronunciationText }}</strong><span>{{ feedbackCategory(row.category) }} · {{ feedbackStatus(row.status) }}</span><span class="preserve-lines">{{ row.description }}</span><small>{{ new Date(row.createdAt).toLocaleString() }}</small></button></li></ul>
    <div v-if="rows.total > 20" class="pagination"><button :disabled="busy || !rows.page" @click="load(rows.page - 1)">上一页</button><span>{{ rows.page + 1 }}</span><button :disabled="busy || (rows.page + 1) * 20 >= rows.total" @click="load(rows.page + 1)">下一页</button></div>
    <dialog ref="dialog" class="config-dialog" aria-labelledby="audio-feedback-admin-title" @cancel="busy && $event.preventDefault()">
      <template v-if="selected"><h2 id="audio-feedback-admin-title">处理发音反馈</h2>
        <p v-if="error" class="error" role="alert">{{ error }} <button class="quiet" :disabled="busy" @click="open(selected.report.id)">重新读取</button></p><p v-if="message" role="status">{{ message }}</p>
        <h3>{{ selected.written }}</h3><p>{{ feedbackCategory(selected.report.category) }} · {{ feedbackStatus(selected.report.status) }}</p><p class="preserve-lines">{{ selected.report.description }}</p>
        <p class="note">提交时：词典第 {{ selected.report.reportedRevision }} 版 · {{ selected.report.pronunciationText }}</p><p class="note">当前公开发音：{{ selected.currentPronunciation || '不可用' }}<span v-if="selected.currentRevision"> · 第 {{ selected.currentRevision }} 版</span></p>
        <div v-if="selected.available" class="actions"><AudioButton :key="selected.currentAudioVersionId ?? 'missing'" :auto="false" :feedback="false" :allow-regenerate="false" :request="{ entryId: selected.report.entryId, resourceId: selected.report.resourceId, kind: selected.report.kind, scope: 'PUBLISHED', pronunciationText: selected.currentPronunciation }" /><button class="secondary" :disabled="busy || selected.report.status !== 'PENDING'" @click="regenerate">生成新的公共音频</button></div>
        <div class="actions"><button class="quiet" :disabled="busy" @click="edit">打开基础词典修正</button><router-link :to="'/dictionary/' + selected.report.entryId" @click="suspend">查看公开词条</router-link></div>
        <template v-if="selected.report.status === 'PENDING'"><label>处理说明<textarea v-model="note" :disabled="busy" maxlength="1000" rows="3" placeholder="填写修正内容或无需修复的原因，用户可查看"></textarea></label><div class="actions"><button :disabled="busy" @click="resolve('RESOLVED')">确认已修复</button><button class="secondary" :disabled="busy" @click="resolve('DISMISSED')">无需修复并关闭</button></div></template>
        <p v-else class="preserve-lines">处理说明：{{ selected.report.resolutionNote }}</p>
        <button class="quiet" :disabled="busy" @click="suspend">关闭</button>
      </template>
    </dialog>
  </section>
</template>
