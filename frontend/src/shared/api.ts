/** HTTP 非成功响应，保留状态码和稳定错误码供页面判断。 */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, detail = code) {
    super(detail)
  }
}
/** 构建时指定后端源站；留空时继续使用本地开发和 Docker 的同源代理。 */
export function apiUrl(path: string, base = import.meta.env.VITE_API_BASE_URL ?? ''): string {
  if (!path.startsWith('/api/') || path.includes('\\')) throw new Error('无效的 API 路径。')
  const origin = base.trim().replace(/\/+$/, '')
  if (!origin) return path
  const url = new URL(origin)
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) throw new Error('API 地址必须是 HTTP(S) 源站地址。')
  return url.origin + path
}
/** 所有接口和音频共用后端地址，并携带跨源会话 Cookie；不向任意外部地址发送凭据。 */
export function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(apiUrl(path), { ...init, credentials: 'include' })
}
/** 解析成功正文，并把后端错误正文转换为统一异常。 */
async function parse<T>(response: Response): Promise<T> {
  if (response.ok) {
    if (response.status === 204 || !response.headers.get('content-type')?.includes('json')) return undefined as T
    return response.json() as Promise<T>
  }
  const body = await response.json().catch(() => ({})) as { code?: string; detail?: string; accountId?: string }
  if (response.status === 401 && body.code === 'ACCOUNT_DELETED' && body.accountId && /^[0-9a-f-]{36}$/i.test(body.accountId)) {
    // 仅在服务器明确确认旧会话所属账号已删除时清理，普通过期/禁用不能清除离线记录。
    const { retireAccount } = await import('../platform/web/accountClosure')
    try { await retireAccount({ serverId: window.location.origin, userId: body.accountId }) }
    catch { body.detail = '账号已注销；本机缓存清理失败，请在浏览器恢复存储后清理本站数据。' }
  }
  throw new ApiError(response.status, body.code ?? body.detail ?? 'REQUEST_FAILED', body.detail ?? body.code ?? '请求失败，请重试。')
}
/** 发起携带后端会话 Cookie 的 GET 请求。 */
export async function getJson<T>(path: string): Promise<T> {
  return parse<T>(await apiFetch(path))
}
/** 从后端取得当前会话的 CSRF 请求头名称和令牌。 */
async function csrf(): Promise<{ headerName: string; token: string }> {
  return getJson('/api/v1/auth/csrf')
}
/** 提交表单编码请求，供登录和退出端点使用。 */
export async function postForm<T>(path: string, values: Record<string, string>): Promise<T> {
  const token = await csrf()
  return parse<T>(await apiFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [token.headerName]: token.token },
    body: new URLSearchParams(values),
  }))
}
/** 提交 JSON 请求，并自动附带 CSRF 令牌。 */
export async function postJson<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  const token = await csrf()
  return parse<T>(await apiFetch(path, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/json', [token.headerName]: token.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  }))
}
/** 保存完整个人内容表单；与 POST 一样先获取当前会话的 CSRF。 */
export async function putJson<T>(path: string, body: unknown): Promise<T> {
  const token = await csrf()
  return parse<T>(await apiFetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: JSON.stringify(body),
  }))
}

/** 上传文件时让浏览器生成 multipart boundary，并继续使用同一套 CSRF 会话保护。 */
export async function postFile<T>(path: string, field: string, file: File, values: Record<string, string> = {}): Promise<T> {
  const token = await csrf()
  const body = new FormData()
  body.append(field, file)
  for (const [name, value] of Object.entries(values)) body.append(name, value)
  return parse<T>(await apiFetch(path, {
    method: 'POST',
    headers: { [token.headerName]: token.token }, body,
  }))
}

/** 发起带 CSRF 令牌的 DELETE 请求，供移除单词本关联和删除单词本使用。 */
export async function deleteJson<T = void>(path: string): Promise<T> {
  const token = await csrf()
  return parse<T>(await apiFetch(path, {
    method: 'DELETE',
    headers: { [token.headerName]: token.token },
  }))
}
