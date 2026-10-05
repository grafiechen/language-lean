<script setup lang="ts">
import { IonApp, IonRouterOutlet } from '@ionic/vue'
import { useAuth } from './features/auth/store'
import { networkOnline } from './platform/web/connectivity'
import { onMounted, onBeforeUnmount } from 'vue'
import { ACCOUNT_CLOSED_EVENT, ACCOUNT_CLOSED_CHANNEL } from './platform/web/accountClosure'
import type { AccountScope } from './core/reviews'
const auth = useAuth()
let channel: BroadcastChannel | undefined
/** 注销通知只影响匹配的账号，同源其他标签页也退出旧训练。 */
function closed(scope: AccountScope) {
  if (scope?.serverId === window.location.origin && auth.accountClosed(scope.userId)) window.location.assign('/login?closed=1')
}
function locallyClosed(event: Event) { closed((event as CustomEvent<AccountScope>).detail) }
onMounted(() => {
  window.addEventListener(ACCOUNT_CLOSED_EVENT, locallyClosed)
  if ('BroadcastChannel' in window) { channel = new BroadcastChannel(ACCOUNT_CLOSED_CHANNEL); channel.onmessage = event => closed(event.data) }
})
onBeforeUnmount(() => { window.removeEventListener(ACCOUNT_CLOSED_EVENT, locallyClosed); channel?.close() })
</script>
<template><ion-app><ion-router-outlet /><aside v-if="auth.user && (auth.mode === 'CACHED' || !networkOnline)" class="offline-banner" role="status">{{ auth.user.username }} · 使用本地学习缓存 <router-link to="/login?reauth=1">登录后同步</router-link><router-link to="/offline">离线账号与内容</router-link></aside></ion-app></template>
