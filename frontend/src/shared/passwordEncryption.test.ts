import { afterEach, expect, it, vi } from 'vitest'
import { postForm, postJson } from './api'
import { encryptPasswordBody, passwordPath, type PasswordKey } from './passwordEncryption'

afterEach(() => vi.unstubAllGlobals())
const path = '/api/v1/auth/login'
function issued(): PasswordKey {
  return { keyId: '7cbb58b2-5c2a-47bb-993f-df9f7f09a109', algorithm: 'AES-256-GCM',
    key: btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))), expiresAt: '2099-01-01T00:00:00Z' }
}

it('authenticates the whole payload, rejects tampering and binds it to the intended endpoint', async () => {
  const server = issued(), originalKey = server.key
  const envelope = await encryptPasswordBody(path, { identifier: 'owner', password: '日本語12!' }, server)
  expect(server.key).toBe('')
  const raw = (value: string) => Uint8Array.from(atob(value), char => char.charCodeAt(0))
  const key = await crypto.subtle.importKey('raw', raw(originalKey), 'AES-GCM', false, ['decrypt'])
  const options = (endpoint: string) => ({ name: 'AES-GCM', iv: raw(envelope.nonce),
    additionalData: new TextEncoder().encode(`password-v1|${envelope.keyId}|${endpoint}`), tagLength: 128 })
  expect(JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt(options(path), key, raw(envelope.ciphertext)))))
    .toEqual({ identifier: 'owner', password: '日本語12!' })
  await expect(crypto.subtle.decrypt(options('/api/v1/auth/password-reset'), key, raw(envelope.ciphertext))).rejects.toThrow()
  const tampered = raw(envelope.ciphertext); tampered[0] = tampered[0]! ^ 1
  await expect(crypto.subtle.decrypt(options(path), key, tampered)).rejects.toThrow()
})

it('fetches a fresh server key for every password submission and never sends raw passwords', async () => {
  const fetch = vi.fn().mockImplementation(async (url: string) => {
    if (url.endsWith('/csrf')) return Response.json({ headerName: 'X-XSRF-TOKEN', token: 'csrf' })
    if (url.endsWith('/password-key')) return Response.json(issued())
    return Response.json({ authenticated: true })
  })
  vi.stubGlobal('fetch', fetch)
  await postForm(path, { identifier: 'owner', password: 'Secret12!' })
  await postJson('/api/v1/auth/password-reset', { token: 'reset-secret', password: 'Secret12!', confirmation: 'Secret12!' })
  const submits = fetch.mock.calls.filter(([url]) => !url.endsWith('/csrf') && !url.endsWith('/password-key'))
  expect(submits).toHaveLength(2)
  for (const [, init] of submits) {
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.body).not.toContain('Secret12!'); expect(init.body).not.toContain('reset-secret')
    expect(JSON.parse(init.body)).toEqual({ keyId: expect.any(String), nonce: expect.any(String), ciphertext: expect.any(String) })
  }
  expect(fetch.mock.calls.filter(([url]) => url.endsWith('/password-key'))).toHaveLength(2)
})

it('refuses an insecure browser without falling back to plaintext or requesting a key', async () => {
  vi.stubGlobal('crypto', {})
  const fetch = vi.fn().mockResolvedValue(Response.json({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }))
  vi.stubGlobal('fetch', fetch)
  await expect(postForm(path, { password: 'Secret12!' })).rejects.toThrow('HTTPS')
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(passwordPath('/api/v1/auth/logout')).toBe(false)
  expect(passwordPath('/api/v1/admin/accounts/7cbb58b2-5c2a-47bb-993f-df9f7f09a109/delete')).toBe(true)
})
