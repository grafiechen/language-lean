<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { IonPage, IonContent, onIonViewWillEnter, onIonViewWillLeave } from '@ionic/vue'
import { postJson } from '../shared/api'

// URL fragment 不会发送给 HTTP 服务器；读取后立即从地址栏移除，令牌只保存在页面内存。
const token = ref(new URLSearchParams(window.location.hash.slice(1)).get('token') ?? '')
window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
const password = ref(''), confirmation = ref(''), busy = ref(false), error = ref(''), complete = ref(false)
/** 成功后清除页面里的密码和令牌；旧会话由服务器统一拒绝。 */
async function submit() {
  if (busy.value) return
  if (password.value !== confirmation.value) { error.value = '两次输入的新密码不一致'; return }
  busy.value = true; error.value = ''
  try {
    await postJson('/api/v1/auth/password-reset', { token: token.value, password: password.value, confirmation: confirmation.value })
    complete.value = true; token.value = ''; password.value = ''; confirmation.value = ''
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重置失败，请重试' }
  finally { busy.value = false }
}
onBeforeUnmount(() => { token.value = ''; password.value = ''; confirmation.value = '' })
/** Ionic 会缓存页面，离开时也清理凭据，再次进入时接收新的邮件链接。 */
onIonViewWillLeave(() => { token.value = ''; password.value = ''; confirmation.value = '' })
onIonViewWillEnter(() => {
  const next = new URLSearchParams(window.location.hash.slice(1)).get('token')
  if (next) {
    token.value = next; complete.value = false; error.value = ''
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search)
  }
})
</script>
<template><ion-page><ion-content><main class="login">
  <p class="brand">LANGUAGE LEAN</p><h1>重置密码</h1>
  <p v-if="complete" role="status">密码已重置，请使用新密码重新登录。离线学习记录仍然保留。</p>
  <template v-else-if="token"><p class="note">至少 8 位，必须包含字母、数字和特殊字符。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <form @submit.prevent="submit"><fieldset :disabled="busy">
      <label>新密码<input v-model="password" type="password" required minlength="8" maxlength="72" autocomplete="new-password"></label>
      <label>重复新密码<input v-model="confirmation" type="password" required minlength="8" maxlength="72" autocomplete="new-password"></label>
      <button>{{ busy ? '正在重置…' : '保存新密码' }}</button>
    </fieldset></form>
  </template>
  <p v-else class="error">链接缺少重置凭据，请从邮件打开完整链接，或重新申请。</p>
  <p><router-link to="/login?reauth=1">返回登录</router-link> · <router-link to="/forgot-password">重新申请链接</router-link></p>
</main></ion-content></ion-page></template>
