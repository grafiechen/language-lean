<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { IonPage, IonContent, IonButton, IonSpinner, IonInput } from '@ionic/vue'
import { useLanguages } from '../features/languages/store'
import { useAuth } from '../features/auth/store'

const languages = useLanguages()
const auth = useAuth()
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const passwordChanged = ref(false)

onMounted(() => languages.refresh())
/** 修改初始密码，并在成功后清空页面中的敏感输入。 */
async function changePassword() {
  passwordChanged.value = await auth.changePassword(currentPassword.value, newPassword.value, confirmation.value)
  if (passwordChanged.value) currentPassword.value = newPassword.value = confirmation.value = ''
}
</script>
<template>
  <ion-page><ion-content>
    <main>
      <header class="account-bar">
        <p class="brand">LANGUAGE LEAN</p>
        <ion-button fill="clear" size="small" @click="auth.logout()">退出 {{ auth.user?.username }}</ion-button>
      </header>
      <h1>让熟悉的词，<br>也能听得出来。</h1>
      <p class="intro">配合你的单词本，练习听觉回忆。</p>
      <section v-if="auth.user?.mustChangePassword" aria-labelledby="password-title">
        <h2 id="password-title">修改初始密码</h2>
        <form class="password-form" @submit.prevent="changePassword">
          <ion-input v-model="currentPassword" type="password" label="当前密码" label-placement="stacked" fill="outline" />
          <ion-input v-model="newPassword" type="password" label="新密码（至少 8 位，含字母、数字和特殊字符）" label-placement="stacked" fill="outline" />
          <ion-input v-model="confirmation" type="password" label="重复新密码" label-placement="stacked" fill="outline" />
          <p v-if="auth.error" class="error" role="alert">{{ auth.error }}</p>
          <ion-button type="submit" :disabled="auth.busy">保存新密码</ion-button>
        </form>
      </section>
      <p v-if="passwordChanged" role="status">密码已更新。</p>
      <section aria-labelledby="languages-title">
        <h2 id="languages-title">学习语言</h2>
        <ion-spinner v-if="languages.loading" aria-label="正在加载语言" />
        <div v-else-if="languages.error" role="status"><p>{{ languages.error }}</p><ion-button @click="languages.refresh()">重新连接</ion-button></div>
        <ul v-else-if="languages.items.length"><li v-for="language in languages.items" :key="language.code">{{ language.displayName }}</li></ul>
        <p v-else>暂未启用学习语言。</p>
      </section>
      <p class="note">词典与复习功能正在准备中。</p>
    </main>
  </ion-content></ion-page>
</template>
