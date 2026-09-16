/** HTTP 非成功响应，保留状态码和稳定错误码供页面判断。 */
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code)
  }
}
/** 解析成功正文，并把后端错误正文转换为统一异常。 */
async function parse<T>(response: Response): Promise<T> {
  if (response.ok) {
    if (response.status === 204 || !response.headers.get('content-type')?.includes('json')) return undefined as T
    return response.json() as Promise<T>
  }
  const body = await response.json().catch(() => ({})) as { code?: string; detail?: string }
  throw new ApiError(response.status, body.code ?? body.detail ?? 'REQUEST_FAILED')
}
/** 发起携带同源会话 Cookie 的 GET 请求。 */
export async function getJson<T>(path: string): Promise<T> {
  return parse<T>(await fetch(path, { credentials: 'same-origin' }))
}
/** 从后端取得当前会话的 CSRF 请求头名称和令牌。 */
async function csrf(): Promise<{ headerName: string; token: string }> {
  return getJson('/api/v1/auth/csrf')
}
/** 提交表单编码请求，供登录和退出端点使用。 */
export async function postForm<T>(path: string, values: Record<string, string>): Promise<T> {
  const token = await csrf()
  return parse<T>(await fetch(path, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [token.headerName]: token.token },
    body: new URLSearchParams(values),
  }))
}
/** 提交 JSON 请求，并自动附带 CSRF 令牌。 */
export async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const token = await csrf()
  return parse<T>(await fetch(path, {
    method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  }))
}
