/** 网络密码接口清单；新增密码操作时必须同时登记后端清单。 */
export function passwordPath(path: string): boolean {
  return ['/api/v1/auth/login', '/api/v1/auth/password', '/api/v1/auth/password-reset', '/api/v1/auth/close-account'].includes(path)
    || /^\/api\/v1\/admin\/accounts\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/delete$/i.test(path)
}
export interface PasswordKey { keyId: string; algorithm: string; key: string; expiresAt: string }
/** 密钥仅由后台分配；前端随机生成本次GCM的12字节IV，不生成或缓存共享密钥。 */
export async function encryptPasswordBody(path: string, body: unknown, issued: PasswordKey) {
  if (!globalThis.crypto?.subtle) throw new Error('密码加密需要 HTTPS 或 localhost，请使用安全地址访问。')
  if (issued.algorithm !== 'AES-256-GCM') throw new Error('不支持的密码加密算法。')
  const keyBytes = Uint8Array.from(atob(issued.key), character => character.charCodeAt(0))
  const plaintext = new TextEncoder().encode(JSON.stringify(body))
  try {
    if (keyBytes.length !== 32) throw new Error('服务器返回了无效的密码密钥。')
    const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt'])
    const nonce = crypto.getRandomValues(new Uint8Array(12))
    const encrypted = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce,
      additionalData: new TextEncoder().encode(`password-v1|${issued.keyId}|${path}`), tagLength: 128 }, key, plaintext)
    const base64 = (value: Uint8Array) => btoa(String.fromCharCode(...value))
    return { keyId: issued.keyId, nonce: base64(nonce), ciphertext: base64(new Uint8Array(encrypted)) }
  } finally { keyBytes.fill(0); plaintext.fill(0); issued.key = '' }
}
