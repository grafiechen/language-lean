let registration: Promise<ServiceWorkerRegistration> | undefined
/** 仅生产构建注册静态应用壳；不影响开发热更新。 */
export function registerOfflineShell(): void {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  registration = navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' })
  void registration.catch(() => undefined)
}
/** 完成离线准备前必须确认应用壳安装，超时不能误报已就绪。 */
export async function requireOfflineShell(): Promise<void> {
  if (!registration) throw new Error('当前运行模式不支持离线启动，请在生产预览或 HTTPS 部署中准备。')
  await registration
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    await Promise.race([navigator.serviceWorker.ready,
      new Promise<never>((_resolve, reject) => { timer = setTimeout(() => reject(new Error('应用离线页面尚未准备完成，请稍后重试。')), 20000) })])
  } finally { if (timer) clearTimeout(timer) }
}
