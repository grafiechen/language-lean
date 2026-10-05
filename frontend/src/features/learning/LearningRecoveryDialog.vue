<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import type { AccountScope } from '../../core/reviews'
import { MAX_BACKUP_BYTES } from '../../platform/web/learningRecoveryFormat'
import { previewLearningRecovery, applyLearningRecovery, type RecoveryPlan } from '../../platform/web/learningRecovery'
import { reviewUploader } from '../../platform/web/reviewSync'
import { learningStorageError } from '../../platform/web/cacheManagement'

const props = defineProps<{ scope: AccountScope; online: boolean; disabled: boolean }>()
const emit = defineEmits<{ restored: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), input = ref<HTMLInputElement | null>(null)
const plan = shallowRef<RecoveryPlan | null>(null), completed = shallowRef<RecoveryPlan | null>(null)
const busy = ref(false), filename = ref(''), error = ref(''), message = ref('')
let text = '', owner: AccountScope | null = null
const count = computed(() => (plan.value?.pending ?? 0) + (plan.value?.drafts ?? 0))
/** 弹窗固定学习归属；文件只留内存，不上传备份正文。 */
function open() {
  if (!props.online || props.disabled) return
  owner = { ...props.scope }; text = ''; filename.value = ''; plan.value = completed.value = null; error.value = message.value = ''
  if (input.value) input.value.value = ''; dialog.value?.showModal()
}
function sameOwner() { return owner?.serverId === props.scope.serverId && owner?.userId === props.scope.userId && props.online }
async function inspect() {
  if (!owner || !sameOwner() || busy.value || !text) return
  busy.value = true; error.value = ''; plan.value = null
  try { const result = await previewLearningRecovery(owner, text); if (!sameOwner()) throw new Error('学习账号已变化，请重新打开恢复窗口。'); plan.value = result }
  catch (cause) { error.value = learningStorageError(cause, '恢复预检失败。') }
  finally { busy.value = false }
}
async function fileChanged(event: Event) {
  plan.value = completed.value = null; error.value = message.value = ''; text = ''
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  filename.value = file.name
  if (file.size > MAX_BACKUP_BYTES) { error.value = '备份文件超过 20 MB，请缩小范围后重试。'; return }
  busy.value = true
  try { text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()) }
  catch { error.value = '请使用有效的 UTF-8 JSON 备份文件。' }
  finally { busy.value = false }
  if (text) await inspect()
}
/** 用户确认后重新核对，成功只恢复本机；上传由独立按钮明确触发。 */
async function restore() {
  if (!plan.value || !sameOwner() || busy.value) return
  busy.value = true; error.value = ''
  try {
    completed.value = await applyLearningRecovery(plan.value); plan.value = null
    message.value = `已恢复 ${completed.value.pending} 条待上传记录、${completed.value.drafts} 份训练草稿。服务器进度尚未因恢复而改变。`
    emit('restored')
  } catch (cause) { error.value = learningStorageError(cause, '恢复失败，原本机数据保留。') }
  finally { busy.value = false }
}
async function upload() {
  if (!owner || !sameOwner() || busy.value) return
  busy.value = true; error.value = ''
  try { const result = await reviewUploader.upload(owner); message.value = `本账号仍有 ${result.remaining} 条记录待上传，已接收记录不会重复计分。`; emit('restored') }
  catch (cause) { error.value = learningStorageError(cause, '上传失败，已恢复记录保留，请稍后补传。') }
  finally { busy.value = false }
}
watch(() => [props.scope.serverId, props.scope.userId, props.online], () => {
  if (owner && !sameOwner()) { plan.value = null; error.value = '学习账号或连接状态已变化，请联网登录所属账号后重新打开。' }
})
</script>
<template>
  <button class="secondary" :disabled="disabled || !online" @click="open">恢复本机学习记录</button>
  <p v-if="!online" class="note">恢复需要联网登录备份所属账号，先核对删除、重置和已上传记录。</p>
  <dialog ref="dialog" class="config-dialog" aria-labelledby="recovery-title" @cancel="busy && $event.preventDefault()" @close="text = ''; plan = null">
    <h2 id="recovery-title">恢复本机学习记录</h2>
    <p class="note">补回当前账号缺失的待上传答题和训练草稿。保留现有记录，已删除或重置的数据跳过；服务器单词本、私人内容和进度不会由旧备份覆盖。备份不含音频文件。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
    <fieldset :disabled="busy || !sameOwner()">
      <label v-if="!completed">选择学习数据 JSON 备份<input ref="input" type="file" accept=".json,application/json" @change="fileChanged"></label>
      <p v-if="filename" class="filename">{{ filename }}</p>
      <div v-if="plan" aria-live="polite">
        <p>可恢复：{{ plan.pending }} 条待上传记录、{{ plan.drafts }} 份训练草稿。</p>
        <p class="note">已上传 {{ plan.accepted }} 条 · 本机已有 {{ plan.existing }} 条 · 已删除或重置 {{ plan.deletedOrReset }} 条 · 冲突 {{ plan.conflicts }} 条 · 保留现有或跳过失效草稿 {{ plan.skippedDrafts }} 份。</p>
        <ul v-if="plan.warnings.length" class="note"><li v-for="warning in plan.warnings" :key="warning">{{ warning }}</li></ul>
        <button :disabled="!count" @click="restore">确认恢复到本机</button>
      </div>
      <button v-if="text && !completed" class="quiet" @click="inspect">重新预检</button>
      <button v-if="completed?.pending" @click="upload">上传本账号待提交记录</button>
    </fieldset>
    <p v-if="busy" role="status">正在核对或恢复，请稍候…</p>
    <button class="secondary" :disabled="busy" @click="dialog?.close()">{{ completed ? '完成' : '关闭' }}</button>
  </dialog>
</template>
<style scoped>.filename { overflow-wrap: anywhere; }</style>
