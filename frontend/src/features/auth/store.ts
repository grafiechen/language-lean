import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError, getJson, postForm, postJson } from '../../shared/api'

/** 当前登录账号在前端需要使用的公开信息。 */
export interface CurrentUser {
  id: string
  username: string
  email: string
  roles: string[]
  mustChangePassword: boolean
}
/** 统一管理会话恢复、登录退出和密码修改状态。 */
export const useAuth = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const checked = ref(false)
  const busy = ref(false)
  const error = ref('')
  const authenticated = computed(() => user.value !== null)

  /** 应用启动时恢复服务端会话；未登录是正常状态并返回 false。 */
  async function restore(): Promise<boolean> {
    try { user.value = await getJson<CurrentUser>('/api/v1/auth/me') }
    catch (cause) {
      if (!(cause instanceof ApiError) || cause.status !== 401) throw cause
      user.value = null
    } finally { checked.value = true }
    return authenticated.value
  }
  /** 使用用户名或邮箱登录，并在成功后刷新当前账号信息。 */
  async function login(identifier: string, password: string): Promise<boolean> {
    busy.value = true
    error.value = ''
    try {
      await postForm('/api/v1/auth/login', { identifier, password })
      await restore()
      return true
    } catch (cause) {
      user.value = null
      error.value = cause instanceof ApiError && cause.status === 401
        ? '用户名、邮箱或密码不正确。' : '暂时无法登录，请稍后重试。'
      return false
    } finally { busy.value = false; checked.value = true }
  }
  /** 注销服务端会话，随后清除本地账号并跳转登录页。 */
  async function logout(): Promise<void> {
    try { await postForm('/api/v1/auth/logout', {}) }
    finally { user.value = null; checked.value = true; window.location.assign('/login') }
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
  return { user, checked, busy, error, authenticated, restore, login, logout, changePassword }
})
