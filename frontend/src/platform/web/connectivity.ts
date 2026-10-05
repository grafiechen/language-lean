import { ref } from 'vue'
/** 浏览器网络状态只决定本地入口，不能代替服务端会话认证。 */
export const networkOnline = ref(typeof navigator === 'undefined' || navigator.onLine)
if (typeof window !== 'undefined') {
  window.addEventListener('online', () => { networkOnline.value = true })
  window.addEventListener('offline', () => { networkOnline.value = false })
}
