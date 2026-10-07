import { expect, it, vi } from 'vitest'
import { runInNewContext } from 'node:vm'
import { renderWorker } from './offlineShell'

/** 在隔离 Worker 环境验证请求边界，避免私有 API 混入公共缓存。 */
function worker() {
  const listeners: Record<string, (event: unknown) => void> = {}
  const match = vi.fn().mockResolvedValue(new Response('static shell'))
  const open = vi.fn().mockResolvedValue({ match, addAll: vi.fn() })
  runInNewContext(renderWorker('test', ['/index.html', '/assets/app.js']), {
    self: { location: { origin: 'https://example.test' }, clients: { claim: vi.fn() },
      addEventListener: (type: string, listener: (event: unknown) => void) => { listeners[type] = listener } },
    caches: { open, keys: vi.fn().mockResolvedValue([]), delete: vi.fn() }, URL, Request, Response, fetch: vi.fn(),
  })
  return { listeners, match, open }
}
it('does not intercept authentication, private audio, mutations, external URLs or unknown files', () => {
  const { listeners, open } = worker()
  for (const request of [
    { method: 'GET', url: 'https://example.test/api/v1/auth/me', mode: 'navigate' },
    { method: 'GET', url: 'https://example.test/api/v1/audio/versions/private', mode: 'cors' },
    { method: 'POST', url: 'https://example.test/assets/app.js', mode: 'cors' },
    { method: 'GET', url: 'https://other.test/assets/app.js', mode: 'cors' },
    { method: 'GET', url: 'https://example.test/private.json', mode: 'cors' },
  ]) {
    const respondWith = vi.fn(); listeners.fetch({ request, respondWith }); expect(respondWith).not.toHaveBeenCalled()
  }
  expect(open).not.toHaveBeenCalled()
})
it('returns the same static shell for unvisited routes without caching personal navigation responses', async () => {
  const { listeners, match } = worker()
  let response: Promise<Response> | undefined
  listeners.fetch({ request: { method: 'GET', url: 'https://example.test/learning/private-book/review', mode: 'navigate' },
    respondWith: (result: Promise<Response>) => { response = result } })
  expect(await (await response!).text()).toBe('static shell')
  expect(match).toHaveBeenCalledWith('/index.html')
})

it('serves a redirected Cloudflare index for offline navigation while retaining its CSP headers', async () => {
  const { listeners, match } = worker()
  const cached = new Response('static shell', { headers: { 'Content-Security-Policy': "frame-ancestors 'none'", 'Content-Type': 'text/html' } })
  Object.defineProperty(cached, 'redirected', { value: true })
  match.mockResolvedValue(cached)
  let response: Promise<Response> | undefined
  listeners.fetch({ request: { method: 'GET', url: 'https://example.test/login', mode: 'navigate', redirect: 'manual' },
    respondWith: (result: Promise<Response>) => { response = result } })
  const result = await response!
  expect(result.redirected).toBe(false)
  expect(result.headers.get('Content-Security-Policy')).toBe("frame-ancestors 'none'")
  expect(await result.text()).toBe('static shell')
})
