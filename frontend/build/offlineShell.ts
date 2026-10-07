import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { Plugin } from 'vite'

/** 公共 Cache Storage 只保存静态应用壳，不处理个人 API 或音频。 */
export function renderWorker(version: string, files: string[]): string {
  return `
const CACHE = 'language-lean-shell-${version}';
const FILES = ${JSON.stringify(files)};
// 新版本等待旧页面关闭，避免训练中途强制刷新和混用脚本。
self.addEventListener('install', event => event.waitUntil((async () => {
  const cache = await caches.open(CACHE);
  await cache.addAll(FILES.map(url => new Request(url, { credentials: 'omit', cache: 'reload' })));
})()));
self.addEventListener('activate', event => event.waitUntil((async () => {
  for (const key of await caches.keys()) if (key.startsWith('language-lean-shell-') && key !== CACHE) await caches.delete(key);
  await self.clients.claim();
})()));
self.addEventListener('fetch', event => {
  const request = event.request, url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
  const path = request.mode === 'navigate' ? '/index.html' : url.pathname;
  if (!FILES.includes(path)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(path);
    // Cloudflare 会把 /index.html 规范化到 /。导航请求拒绝 redirected 响应；
    // 保留正文和包括 CSP 在内的响应头，重新构造无重定向标记的静态应用壳。
    if (cached && request.mode === 'navigate' && cached.redirected)
      return new Response(cached.body, { status: cached.status, statusText: cached.statusText, headers: cached.headers });
    return cached || fetch(request);
  })());
});
`
}
/** 产物内容指纹确定缓存版本；预缓存路由分块，支持未访问页面的离线打开。 */
export function offlineShellPlugin(): Plugin {
  let publicDir = ''
  return { name: 'language-lean-offline-shell', apply: 'build', enforce: 'post',
    configResolved(config) { publicDir = config.publicDir },
    generateBundle(_options, bundle) {
      const names = Object.keys(bundle).filter(name => name === 'index.html' || name.startsWith('assets/')).sort()
      const hash = createHash('sha256')
      for (const name of names) {
        const file = bundle[name]; hash.update(name); hash.update(file.type === 'chunk' ? file.code : file.source)
      }
      const files = [...new Set(['/index.html', '/manifest.webmanifest', '/app-icon.svg', ...names.map(name => '/' + name)])]
      // Worker 逻辑及安全策略变化也必须轮换缓存，不能只依赖页面脚本的指纹。
      hash.update(renderWorker('version-placeholder', files))
      const headersFile = resolve(publicDir, '_headers')
      if (publicDir && existsSync(headersFile)) hash.update(readFileSync(headersFile))
      this.emitFile({ type: 'asset', fileName: 'sw.js', source: renderWorker(hash.digest('hex').slice(0, 20), files) })
    },
  }
}
