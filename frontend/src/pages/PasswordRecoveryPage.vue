<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { IonPage, IonContent } from '@ionic/vue'
import { getJson, postJson } from '../shared/api'

const identifier = ref(''), busy = ref(false), configured = ref(false), checked = ref(false), error = ref(''), message = ref('')
/** 展示配置状态；发送响应不说明账号是否存在。 */
async function check() {
  try { configured.value = (await getJson<{ configured: boolean }>('/api/v1/auth/password-recovery')).configured; checked.value = true }
  catch { error.value = '无法连接服务器，请联网后重试。' }
}
async function submit() {
  if (busy.value || !configured.value || !identifier.value.trim()) return
  busy.value = true; error.value = ''; message.value = ''
  try { message.value = (await postJson<{ message: string }>('/api/v1/auth/password-recovery', { identifier: identifier.value.trim() })).message }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '发送失败，请重试' }
  finally { busy.value = false }
}
onMounted(() => void check())
</script>
<template><ion-page><ion-content><main class="login">
  <p class="brand">LANGUAGE LEAN</p><h1>找回密码</h1>
  <p class="note">输入用户名或注册邮箱。重置链接有效期 30 分钟，只能使用一次。</p>
  <p v-if="checked && !configured" class="note">邮件找回尚未配置，请联系管理员。</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" role="status">{{ message }}</p>
  <form @submit.prevent="submit"><label>用户名或邮箱<input v-model="identifier" required maxlength="320" autocomplete="username"></label><button :disabled="busy || !configured">{{ busy ? '正在发送…' : '发送重置链接' }}</button></form>
  <p><router-link to="/login">返回登录</router-link></p>
</main></ion-content></ion-page></template>
