<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { IonPage, IonContent, IonButton, IonSpinner, IonInput, onIonViewWillLeave } from '@ionic/vue'
import { useLanguages } from '../features/languages/store'
import { useAuth } from '../features/auth/store'
import AccountClosureDialog from '../features/accounts/AccountClosureDialog.vue'

const languages = useLanguages()
const auth = useAuth()
const closure = ref<InstanceType<typeof AccountClosureDialog> | null>(null)
const currentPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const passwordChanged = ref(false)
/** 离开页面或切换账号后，缓存的页面不得保留上一位用户的密码。 */
function clearPasswords() { currentPassword.value = newPassword.value = confirmation.value = '' }
onIonViewWillLeave(clearPasswords)
onBeforeUnmount(clearPasswords)
watch(() => auth.user?.id, clearPasswords)
import { nativeLanguages } from '../features/dictionary/translations'
const nativeLanguage = ref(auth.user?.nativeLanguage ?? 'zh-Hans')
const preferenceSaved = ref(false)
watch(() => auth.user?.nativeLanguage, value => { nativeLanguage.value = value ?? 'zh-Hans' })
async function savePreference() { preferenceSaved.value = false; preferenceSaved.value = await auth.saveNativeLanguage(nativeLanguage.value) }

onMounted(() => languages.refresh())
/** 修改初始密码，并在成功后清空页面中的敏感输入。 */
async function changePassword() {
  if (auth.busy) return
  passwordChanged.value = await auth.changePassword(currentPassword.value, newPassword.value, confirmation.value)
  if (passwordChanged.value) clearPasswords()
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
      <nav class="actions" aria-label="主要功能">
        <router-link class="button-link" to="/dictionary">浏览基础词典</router-link>
        <router-link class="button-link" to="/learning">背词总览</router-link>
        <router-link class="button-link secondary" to="/private-entries">我的私有词条</router-link>
        <router-link class="button-link secondary" to="/contributions">我的投稿</router-link>
        <router-link class="button-link secondary" to="/offline">离线准备</router-link>
        <router-link v-if="auth.user?.roles.includes('ADMIN')" class="button-link secondary" to="/admin">后台管理</router-link>
      </nav>
      <section v-if="auth.serverAuthenticated" aria-labelledby="password-title">
        <h2 id="password-title">{{ auth.user?.mustChangePassword ? '修改初始密码' : '修改密码' }}</h2>
        <form class="password-form" @submit.prevent="changePassword">
          <ion-input v-model="currentPassword" type="password" label="当前密码" label-placement="stacked" fill="outline" />
          <ion-input v-model="newPassword" type="password" label="新密码（至少 8 位，含字母、数字和特殊字符）" label-placement="stacked" fill="outline" />
          <ion-input v-model="confirmation" type="password" label="重复新密码" label-placement="stacked" fill="outline" />
          <p v-if="auth.error" class="error" role="alert">{{ auth.error }}</p>
          <ion-button type="submit" :disabled="auth.busy">保存新密码</ion-button>
        </form>
      </section>
      <p v-if="passwordChanged" role="status">密码已更新。</p>
      <section aria-labelledby="native-language-title">
        <h2 id="native-language-title">母语与译文</h2>
        <form @submit.prevent="savePreference">
          <label>母语<select v-model="nativeLanguage" :disabled="!auth.serverAuthenticated || auth.busy"><option v-for="language in nativeLanguages" :key="language.code" :value="language.code">{{ language.name }}</option></select></label>
          <p class="note">释义和例句优先显示母语译文，缺少时显示原有语言。发音保持学习语言。</p>
          <button type="submit" :disabled="!auth.serverAuthenticated || auth.busy">保存母语</button>
          <p v-if="!auth.serverAuthenticated" class="note">请联网登录后修改，离线学习沿用本账号已保存的设置。</p>
          <p v-if="preferenceSaved" role="status">母语设置已保存。</p>
          <p v-if="auth.error && !auth.user?.mustChangePassword" class="error" role="alert">{{ auth.error }}</p>
        </form>
      </section>
      <section aria-labelledby="languages-title">
        <h2 id="languages-title">学习语言</h2>
        <ion-spinner v-if="languages.loading" aria-label="正在加载语言" />
        <div v-else-if="languages.error" role="status"><p>{{ languages.error }}</p><ion-button @click="languages.refresh()">重新连接</ion-button></div>
        <ul v-else-if="languages.items.length"><li v-for="language in languages.items" :key="language.code">{{ language.displayName }}</li></ul>
        <p v-else>暂未启用学习语言。</p>
      </section>
      <p class="note">先将词条加入单词本，准备内容和音频后即可听音复习。</p>
      <section v-if="auth.serverAuthenticated && auth.user" aria-labelledby="account-closure-title">
        <h2 id="account-closure-title">注销账号</h2><p class="note">删除账号和全部个人学习数据。若只是暂时不使用，可以退出登录。</p>
        <button class="secondary danger" @click="closure?.open({ id: auth.user.id, username: auth.user.username })">注销我的账号</button>
      </section>
      <AccountClosureDialog ref="closure" />
    </main>
  </ion-content></ion-page>
</template>
