<script setup lang="ts">
import { ref } from 'vue'
import { useAuth } from '../auth/store'
import { postJson } from '../../shared/api'
import { retireAccount } from '../../platform/web/accountClosure'

type Target = { id: string; username: string; version?: number }
const props = defineProps<{ admin?: boolean }>()
const emit = defineEmits<{ deleted: [id: string] }>()
const auth = useAuth(), dialog = ref<HTMLDialogElement | null>(null), target = ref<Target | null>(null)
const password = ref(''), confirmation = ref(''), busy = ref(false), error = ref(''), serverDone = ref(false)
let actorId = ''
/** 固定操作身份，另一标签页切换账号时不能误删。 */
function open(value: Target) {
  target.value = { ...value }; actorId = auth.user?.id ?? ''; password.value = ''; confirmation.value = ''; error.value = ''; serverDone.value = false
  dialog.value?.showModal()
}
async function submit() {
  if (busy.value || !target.value) return
  busy.value = true; error.value = ''
  try {
    if (!serverDone.value) {
      if (!auth.serverAuthenticated || auth.user?.id !== actorId) throw new Error('当前登录账号已变化，请重新打开确认窗口。')
      if (props.admin) await postJson(`/api/v1/admin/accounts/${target.value.id}/delete`, { actorId, version: target.value.version, password: password.value, confirmation: confirmation.value })
      else await postJson('/api/v1/auth/close-account', { accountId: target.value.id, password: password.value, confirmation: confirmation.value })
      serverDone.value = true; password.value = ''
    }
    // 服务器已删而本机失败时，只重试清理，不重复注销或关联新同名账号。
    await retireAccount({ serverId: window.location.origin, userId: target.value.id })
    dialog.value?.close(); emit('deleted', target.value.id)
    if (!props.admin) window.location.assign('/login?closed=1')
  } catch (cause) { error.value = (serverDone.value ? '服务器账号已注销；本机缓存清理失败，请点击下方按钮重试。' : '') + (cause instanceof Error ? cause.message : '操作失败，请重试') }
  finally { busy.value = false }
}
defineExpose({ open })
</script>
<template><dialog ref="dialog" class="config-dialog" aria-labelledby="close-account-title" @cancel="(busy || serverDone) && $event.preventDefault()" @close="password = ''">
  <form @submit.prevent="submit"><h2 id="close-account-title">{{ admin ? '删除账号' : '注销账号' }}</h2>
    <p>确认彻底删除 <strong>{{ target?.username }}</strong> 的账号和个人数据。</p>
    <p class="note">单词本、私人词条、个人内容和全部学习进度会删除，无法恢复。已审核公开的词条、来源和许可保留。以后使用相同用户名或邮箱创建账号，也会从零开始。</p>
    <p class="note">本浏览器中该账号的离线缓存和未上传记录也会清除。其他设备断网时无法远程清除其本地副本。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <fieldset :disabled="busy">
      <template v-if="!serverDone">
        <label>{{ admin ? '当前管理员密码' : '当前密码' }}<input v-model="password" type="password" autocomplete="current-password" required></label>
        <label>输入要删除的用户名<input v-model="confirmation" required autocomplete="off" :placeholder="target?.username" maxlength="80"></label>
      </template>
      <div class="actions"><button v-if="!serverDone" type="button" class="secondary" @click="dialog?.close()">取消</button><button class="danger" :disabled="!serverDone && (!password || confirmation !== target?.username)">{{ busy ? '正在处理…' : serverDone ? '重新清理本机缓存' : '确认永久删除' }}</button></div>
    </fieldset>
  </form>
</dialog></template>
<style scoped>
strong { overflow-wrap: anywhere; }
.actions button.danger { color: white; background: #a52f2f; }
</style>
