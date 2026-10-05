import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError, getJson, postForm, postJson } from '../../shared/api'
import { availableCachedAccounts, blockSessionRestore, lastCachedAccount, rememberAccount,
  selectCachedAccountKey, sessionRestoreBlocked } from '../../platform/web/cachedAccounts'
import type { CachedAccountRow } from '../../platform/web/database'

/** 当前登录账号在前端需要使用的公开信息。 */
export interface CurrentUser {
  id: string
  username: string
  email: string
  roles: string[]
  mustChangePassword: boolean
  nativeLanguage?: string
}
/** 统一管理会话恢复、登录退出和密码修改状态。 */
export const useAuth = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const mode = ref<'NONE' | 'ONLINE' | 'CACHED'>('NONE')
  const checked = ref(false)
  const busy = ref(false)
  const error = ref('')
  const authenticated = computed(() => user.value !== null)
  const serverAuthenticated = computed(() => mode.value === 'ONLINE')
  /** 注销通知只影响对应 UUID 的内存身份，其他账号的在线会话保留。 */
  function accountClosed(userId: string) {
    if (user.value?.id !== userId) return false
    user.value = null; mode.value = 'NONE'; checked.value = true
    return true
  }

  /** 缓存账户不具备服务端权限，不能用于后台配置或账户修改。 */
  function useCached(row: CachedAccountRow) {
    user.value = { id: row.userId, username: row.username, email: '', roles: [], mustChangePassword: false, nativeLanguage: row.nativeLanguage ?? 'zh-Hans' }
    mode.value = 'CACHED'; checked.value = true
  }
  /** 允许切换已有准备的离线账户，不删除其他账户尚未同步的学习记录。 */
  async function selectCached(key: string): Promise<boolean> {
    const row = (await availableCachedAccounts(window.location.origin)).find(value => value.accountKey === key)
    if (!row) return false
    selectCachedAccountKey(key); useCached(row); return true
  }

  /** 应用启动时恢复服务端会话；未登录是正常状态并返回 false。 */
  async function restore(): Promise<boolean> {
    if (sessionRestoreBlocked()) { user.value = null; mode.value = 'NONE'; checked.value = true; return false }
    try {
      const current = await getJson<CurrentUser>('/api/v1/auth/me')
      user.value = current; mode.value = 'ONLINE'
      await rememberAccount({ serverId: window.location.origin, userId: current.id }, current.username, current.nativeLanguage).catch(() => {
        error.value = '账号离线缓存未保存，请检查浏览器存储空间。'
      })
    }
    catch (cause) {
      user.value = null; mode.value = 'NONE'
      if (cause instanceof ApiError && cause.code === 'ACCOUNT_DELETED') { error.value = cause.message; return false }
      if (cause instanceof ApiError && cause.status !== 401 && cause.status < 500) throw cause
      const cached = await lastCachedAccount(window.location.origin)
      if (cached) useCached(cached)
    } finally { checked.value = true }
    return authenticated.value
  }
  /** 使用用户名或邮箱登录，并在成功后刷新当前账号信息。 */
  async function login(identifier: string, password: string): Promise<boolean> {
    busy.value = true
    error.value = ''
    try {
      await postForm('/api/v1/auth/login', { identifier, password })
      // 显式登录成功后解除退出抑制，再重新验证当前服务器身份。
      localStorage.removeItem('language-lean.explicit-logout')
      await restore()
      if (!serverAuthenticated.value) { error.value = '尚未确认在线登录，请重新连接后重试。'; return false }
      return true
    } catch (cause) {
      user.value = null; mode.value = 'NONE'
      error.value = cause instanceof ApiError && cause.status === 401
        ? '用户名、邮箱或密码不正确。' : '暂时无法登录，请稍后重试。'
      return false
    } finally { busy.value = false; checked.value = true }
  }
  /** 注销服务端会话，随后清除本地账号并跳转登录页。 */
  async function logout(): Promise<void> {
    blockSessionRestore()
    try { if (mode.value === 'ONLINE') await postForm('/api/v1/auth/logout', {}) }
    catch { /* 离线退出只清除当前选择，保留待上传事件；在线登录前不自动恢复会话。 */ }
    finally { user.value = null; mode.value = 'NONE'; checked.value = true; window.location.assign('/login') }
  }
  /** 校验原密码及两次新密码后修改密码。 */
  async function changePassword(currentPassword: string, newPassword: string, confirmation: string): Promise<boolean> {
    busy.value = true
    error.value = ''
    try {
      await postJson('/api/v1/auth/password', { currentPassword, newPassword, confirmation })
      await restore()
      return true
    } catch (cause) {
      error.value = cause instanceof ApiError && cause.status === 400
        ? '请检查原密码、新密码和确认密码。新密码至少 8 位，且必须包含字母、数字和特殊字符。' : '暂时无法修改密码，请稍后重试。'
      return false
    } finally { busy.value = false }
  }
  /** 偏好必须在线保存到所属账户；成功后刷新并缓存以供离线显示。 */
  async function saveNativeLanguage(nativeLanguage: string): Promise<boolean> {
    if (!serverAuthenticated.value) { error.value = '请联网登录后修改母语。'; return false }
    busy.value = true; error.value = ''
    try { await postJson('/api/v1/auth/preferences', { nativeLanguage }); await restore(); return serverAuthenticated.value }
    catch (cause) { error.value = cause instanceof Error ? cause.message : '母语保存失败'; return false }
    finally { busy.value = false }
  }
  return { user, mode, serverAuthenticated, checked, busy, error, authenticated, restore, selectCached, login, logout, changePassword, saveNativeLanguage, accountClosed }
})
