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
import { registerOfflineShell } from './platform/web/offlineShell'

registerOfflineShell()

const pinia = createPinia()
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', component: HomePage, meta: { requiresAuth: true } },
    { path: '/login', component: LoginPage },
    { path: '/forgot-password', component: () => import('./pages/PasswordRecoveryPage.vue') },
    { path: '/reset-password', component: () => import('./pages/PasswordResetPage.vue') },
    { path: '/offline', component: () => import('./pages/OfflinePage.vue') },
    { path: '/admin', component: () => import('./pages/AdminPage.vue'), meta: { requiresAuth: true, requiresAdmin: true, requiresServer: true } },
    { path: '/dictionary/:id?', component: () => import('./pages/DictionaryPage.vue'), meta: { requiresAuth: true, requiresServer: true } },
    { path: '/learning', component: () => import('./pages/LearningPage.vue'), meta: { requiresAuth: true } },
    { path: '/private-entries', component: () => import('./pages/PrivateEntriesPage.vue'), meta: { requiresAuth: true, requiresServer: true } },
    { path: '/contributions', component: () => import('./pages/ContributionsPage.vue'), meta: { requiresAuth: true, requiresServer: true } },
    { path: '/learning/items/:itemId', component: () => import('./pages/LearningContentPage.vue'), meta: { requiresAuth: true } },
    { path: '/learning/:wordbookId/review', component: () => import('./pages/TrainingPage.vue'), meta: { requiresAuth: true } },
  ],
})
/** 在首次导航时恢复会话，并保护需要登录的业务页面。 */
router.beforeEach(async to => {
  const auth = useAuth(pinia)
  if (!auth.checked) {
    try { await auth.restore() } catch { /* The login page handles connectivity failures. */ }
  }
  if (to.meta.requiresAuth && !auth.authenticated) return '/login'
  if (to.meta.requiresServer && (!auth.serverAuthenticated || !navigator.onLine)) return '/offline'
  if (to.meta.requiresAdmin && !auth.user?.roles.includes('ADMIN')) return '/'
  if (to.path === '/login' && auth.serverAuthenticated && to.query.reauth !== '1') return '/'
})
const app = createApp(App).use(IonicVue).use(pinia).use(router)
router.isReady().then(() => app.mount('#app'))
