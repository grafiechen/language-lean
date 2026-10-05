import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { offlineShellPlugin } from './build/offlineShell'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_')
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
