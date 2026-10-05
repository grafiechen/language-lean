import { afterEach, expect, it, vi } from 'vitest'
import { apiUrl, getJson, postJson } from './api'

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals() })

it('keeps local proxy paths and resolves a separately deployed backend', () => {
  expect(apiUrl('/api/v1/auth/me', '')).toBe('/api/v1/auth/me')
  expect(apiUrl('/api/v1/dictionary?q=ねこ', 'https://api.example.com/')).toBe('https://api.example.com/api/v1/dictionary?q=ねこ')
  expect(() => apiUrl('//untrusted.example.com', 'https://api.example.com')).toThrow()
  expect(() => apiUrl('/api/\\untrusted.example.com', '')).toThrow()
  expect(() => apiUrl('/api/v1/auth/me', 'https://api.example.com/api')).toThrow()
  expect(() => apiUrl('/api/v1/auth/me', 'https://user:password@api.example.com')).toThrow()
})

it('uses the API origin and credentials for both CSRF retrieval and writes', async () => {
  vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.com')
  const fetch = vi.fn().mockResolvedValueOnce(Response.json({ headerName: 'X-XSRF-TOKEN', token: 'session-token' }))
    .mockResolvedValueOnce(Response.json({ saved: true }))
  vi.stubGlobal('fetch', fetch)
  expect(await postJson('/api/v1/learning/reviews', { rating: 3 })).toEqual({ saved: true })
  expect(fetch).toHaveBeenNthCalledWith(1, 'https://api.example.com/api/v1/auth/csrf', { credentials: 'include' })
  expect(fetch).toHaveBeenNthCalledWith(2, 'https://api.example.com/api/v1/learning/reviews', expect.objectContaining({
    credentials: 'include', method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'session-token' },
  }))
})

it('retains same-origin behavior when no deployment origin is configured', async () => {
  vi.stubEnv('VITE_API_BASE_URL', '')
  const fetch = vi.fn().mockResolvedValue(Response.json({ username: 'test' }))
  vi.stubGlobal('fetch', fetch)
  await getJson('/api/v1/auth/me')
  expect(fetch).toHaveBeenCalledWith('/api/v1/auth/me', { credentials: 'include' })
})
