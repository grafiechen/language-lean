import { createHash } from 'node:crypto'
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
    return await cache.match(path) || fetch(request);
  })());
});
`
}
/** 产物内容指纹确定缓存版本；预缓存路由分块，支持未访问页面的离线打开。 */
export function offlineShellPlugin(): Plugin {
  return { name: 'language-lean-offline-shell', apply: 'build', enforce: 'post',
    generateBundle(_options, bundle) {
      const names = Object.keys(bundle).filter(name => name === 'index.html' || name.startsWith('assets/')).sort()
      const hash = createHash('sha256')
      for (const name of names) {
        const file = bundle[name]; hash.update(name); hash.update(file.type === 'chunk' ? file.code : file.source)
      }
      const files = [...new Set(['/index.html', '/manifest.webmanifest', '/app-icon.svg', ...names.map(name => '/' + name)])]
      this.emitFile({ type: 'asset', fileName: 'sw.js', source: renderWorker(hash.digest('hex').slice(0, 20), files) })
    },
  }
}
