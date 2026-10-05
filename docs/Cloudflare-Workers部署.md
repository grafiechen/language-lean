# Cloudflare Workers 前端部署

本项目使用 Vite 编译 Vue，Wrangler 上传静态页面。无需添加额外的 Cloudflare Vite 插件；后端 API 继续独立部署。

## Git 构建设置

连接 GitHub 仓库后配置：

| 项目 | 值 |
| --- | --- |
| Worker 名称 | `language-lean`（与 `frontend/wrangler.jsonc` 一致） |
| 根目录 | `/frontend` |
| 构建命令 | 留空（Wrangler 在部署前自动执行 `npm run build`） |
| 部署命令 | `npx wrangler deploy` |
| 分支控制 / 生产分支 | `main` |

Cloudflare 根据锁文件自动安装依赖。已创建的 Worker 可在 Settings → Build → Branch control 修改生产分支；推送到该分支后自动构建部署。

`wrangler.jsonc` 已配置自定义构建命令，执行 `npx wrangler deploy` 会先完成 TypeScript 检查和 Vite 编译，再上传生成的 `dist`。无需提交 `dist` 到 Git。若控制台此前填写了 `npm run build`，可以清空以避免重复编译；编译失败时不会继续发布。

在 **Build 的环境变量**中设置 `VITE_API_BASE_URL=https://api.example.com`（替换为实际后端地址，不含 `/api` 路径），可设置 `NODE_VERSION=22`。Vite 会把地址编译进 JavaScript，仅修改 Worker 运行时变量不生效，改地址后需重新构建。

`frontend/.env.example` 默认留空，便于本地开发继续走 Vite 或 Docker 的 `/api` 同源代理。生产分开部署必须设置 API 地址，否则请求会发往静态前端域名。

## 后端与域名

后端环境设置 `CORS_ALLOWED_ORIGINS=https://learn.example.com`，填写完整前端源站，不含末尾斜杠；多个源站用逗号分隔。允许携带会话 Cookie 和 CSRF 令牌，未列出的跨域来源会被拒绝。

在 Workers 的 Settings → Domains & Routes 添加前端自定义域名。当前会话 Cookie 使用 `SameSite=Strict`，生产前端和 API 应使用同一主域名下的 HTTPS 子域名，例如上面的 `learn.example.com` 与 `api.example.com`。`workers.dev` 可预览页面，但不能依靠这个不同站点的域名完成生产登录。

后端生产继续启用 `SESSION_COOKIE_SECURE=true`，由可信 HTTPS 反向代理转发。请勿为了预览把会话 Cookie 的保护关掉。

`wrangler.jsonc` 提供 Vue 页面导航的路由回退，刷新单词详情、登录、训练页面都能打开应用；`public/_headers` 让离线 Service Worker 及时更新，带内容哈希的静态资源长期缓存。`worker.ts` 只处理缺失资源，返回不可缓存的 404，避免把 HTML 当作脚本缓存。已存在的静态资源和浏览器页面导航由 Assets 直接服务；API 和音频读取使用配置的后端地址，不进入静态页面缓存。

## 本地验证

```bash
cd frontend
npm ci
npm test
npx wrangler deploy --dry-run
```

真实域名、账号、Cloudflare 凭据及服务器部署记录保存在项目外的私有运维目录和平台配置中，不提交到仓库。
