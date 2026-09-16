<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { IonPage, IonContent, IonInput, IonButton } from '@ionic/vue'
import { useAuth } from '../features/auth/store'
const identifier = ref('')
const password = ref('')
const auth = useAuth()
const router = useRouter()
/** 提交登录信息；成功后进入受保护的首页。 */
async function submit() {
  if (!identifier.value.trim() || !password.value) return
  if (await auth.login(identifier.value.trim(), password.value)) await router.replace('/')
}
</script>
<template>
  <ion-page><ion-content>
    <main class="login">
      <p class="brand">LANGUAGE LEAN</p>
      <h1>继续学习</h1>
      <form @submit.prevent="submit">
        <label>用户名或邮箱<ion-input v-model="identifier" autocomplete="username" fill="outline" /></label>
        <label>密码<ion-input v-model="password" type="password" autocomplete="current-password" fill="outline" /></label>
        <p v-if="auth.error" class="error" role="alert">{{ auth.error }}</p>
        <ion-button expand="block" type="submit" :disabled="auth.busy || !identifier.trim() || !password">
          {{ auth.busy ? '正在登录…' : '登录' }}
        </ion-button>
      </form>
      <p class="note">第一版账号由管理员创建。</p>
    </main>
  </ion-content></ion-page>
</template>
