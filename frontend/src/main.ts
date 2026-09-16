import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { IonicVue } from '@ionic/vue'
import { createRouter, createWebHistory } from '@ionic/vue-router'
import App from './App.vue'
import HomePage from './pages/HomePage.vue'
import LoginPage from './pages/LoginPage.vue'
import { useAuth } from './features/auth/store'
import '@ionic/vue/css/core.css'
import '@ionic/vue/css/normalize.css'
import '@ionic/vue/css/structure.css'
import '@ionic/vue/css/typography.css'
import './style.css'

const pinia = createPinia()
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', component: HomePage, meta: { requiresAuth: true } },
    { path: '/login', component: LoginPage },
  ],
})
/** 在首次导航时恢复会话，并保护需要登录的业务页面。 */
router.beforeEach(async to => {
  const auth = useAuth(pinia)
  if (!auth.checked) {
    try { await auth.restore() } catch { /* The login page handles connectivity failures. */ }
  }
  if (to.meta.requiresAuth && !auth.authenticated) return '/login'
  if (to.path === '/login' && auth.authenticated) return '/'
})
const app = createApp(App).use(IonicVue).use(pinia).use(router)
router.isReady().then(() => app.mount('#app'))
