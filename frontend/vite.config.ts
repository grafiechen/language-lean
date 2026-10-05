import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { offlineShellPlugin } from './build/offlineShell'

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, '.', 'VITE_')
  // Workers 没有本地 /api 代理，缺失构建变量时禁止发布会向前端源站发送 API 的页面。
  if (command === 'build' && process.env.WORKERS_CI === '1') {
    const origin = env.VITE_API_BASE_URL?.trim()
    if (!origin) throw new Error('请在 Cloudflare Settings > Build > 构建变量和密钥中设置 VITE_API_BASE_URL，然后重新构建；运行时变量不能替代构建变量。')
    let url: URL
    try { url = new URL(origin) }
    catch { throw new Error('VITE_API_BASE_URL 必须是完整 HTTPS 后端源站地址。') }
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/')
      throw new Error('VITE_API_BASE_URL 必须是 HTTPS 后端源站地址，不含 /api 路径、账号或查询参数。')
  }
  return {
    plugins: [vue(), offlineShellPlugin()],
    server: {
      host: env.VITE_DEV_HOST || '127.0.0.1',
      // WSL Docker 挂载 Windows 工作区时使用轮询，保证保存文件后稳定触发 HMR。
      watch: { usePolling: env.VITE_USE_POLLING === 'true', interval: 500 },
      proxy: { '/api': env.VITE_API_PROXY_TARGET || 'http://localhost:8080' },
    },
  }
})
