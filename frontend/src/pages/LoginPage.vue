<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRoute } from 'vue-router'
import { IonPage, IonContent, IonInput, IonButton, onIonViewWillLeave } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
const identifier = ref('')
const password = ref('')
const auth = useAuth()
const router = useRouter()
const route = useRoute()
/** Ionic 隐藏页面不会卸载，离开登录页时也必须清除密码。 */
function clearPassword() { password.value = '' }
onIonViewWillLeave(clearPassword)
onBeforeUnmount(clearPassword)
/** 提交登录信息；成功后进入受保护的首页。 */
async function submit() {
  if (auth.busy || !identifier.value.trim() || !password.value) return
  if (await auth.login(identifier.value.trim(), password.value)) {
    clearPassword()
    await router.replace('/')
  }
}
</script>
<template>
  <ion-page><ion-content>
    <main class="login">
      <p class="brand">LANGUAGE LEAN</p>
      <h1>继续学习</h1>
      <p v-if="route.query.closed === '1'" role="status">账号已注销。再次创建同名账号将从零开始。</p>
      <form @submit.prevent="submit">
        <label>用户名或邮箱<ion-input v-model="identifier" autocomplete="username" fill="outline" /></label>
        <label>密码<ion-input v-model="password" type="password" autocomplete="current-password" fill="outline" /></label>
        <p v-if="auth.error" class="error" role="alert">{{ auth.error }}</p>
        <ion-button expand="block" type="submit" :disabled="auth.busy || !identifier.trim() || !password">
          {{ auth.busy ? '正在登录…' : '登录' }}
        </ion-button>
      </form>
      <p class="note">第一版账号由管理员创建。</p>
      <p><router-link to="/forgot-password">忘记密码？</router-link></p>
      <router-link to="/offline">使用已准备的离线内容</router-link>
    </main>
  </ion-content></ion-page>
</template>
