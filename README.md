# language-lean

自用的语言学习工具。

项目当前已建立日语 Web 版 MVP 的前后端骨架，业务功能逐步实现中。

- frontend：Vue 3 / TypeScript / Ionic Vue / Vite，包含账户、后台词典工作台、语言配置和用户词典页面。
- backend：Java 21 / Spring Boot / PostgreSQL / Flyway / JPA，包含认证、词条草稿、发布历史、封禁和语言配置 API。
- [项目架构与启动说明](./docs/项目架构.md)：目录、模块职责、本地运行和验证命令。

Docker 本地预览：复制 `.env.example` 为 `.env`，设置数据库密码和首次管理员账号，运行 `docker compose up --build -d`，访问 http://localhost:5173。前端、API、PostgreSQL 全部在容器内运行；本机使用 Ubuntu WSL 中的 Docker Engine，无需 Docker Desktop。

停止使用 `docker compose stop`，恢复使用 `docker compose start`。移除容器及网络使用 `docker compose down`，数据库卷仍保留；如果确定连本地账户和数据也不要了，使用 `docker compose down --volumes`。这些命令在项目根目录执行，仅针对本项目。

本机使用 Ubuntu WSL 内的独立 Docker 引擎，无需启动 Docker Desktop。在 PowerShell 中运行：

```powershell
wsl -d Ubuntu -- bash -lc 'cd /mnt/d/gitHome/language-lean && docker compose up --build -d'
```

停止时将上述命令末尾替换为 `docker compose stop`；查看状态替换为 `docker compose ps`。浏览器仍访问 http://localhost:5173。

需要边修改边查看时，切换到开发 Compose：

```powershell
wsl -d Ubuntu -- bash -lc 'cd /mnt/d/gitHome/language-lean && docker compose -f compose.dev.yml up -d'
wsl -d Ubuntu -- bash -lc 'cd /mnt/d/gitHome/language-lean && docker compose -f compose.dev.yml logs -f api web'
```

前端源码保存后由 Vite HMR 更新页面；后端源码保存后由容器自动编译并触发 Spring Boot DevTools 重启。开发与预览使用相同 PostgreSQL 数据卷，切换模式不会清空数据。修改 `pom.xml` 后执行 `docker compose -f compose.dev.yml restart api`，修改 `package-lock.json` 后执行 `docker compose -f compose.dev.yml restart web`；恢复生产式预览时重新执行 `docker compose up --build -d`。

管理员登录后从首页进入“后台词典管理”（/admin）：新增词条 → 保存草稿 → 确认并发布。用户在“浏览基础词典”（/dictionary）查看已发布内容。后台支持多读音、多词义、例句、语言配置、历史恢复草稿和封禁；开源词典批量导入、用户投稿审核和 TTS 尚未接入。

- [项目概要](./docs/项目概要.md)：产品目标、范围和技术方向
- [设计文档](./docs/设计文档.md)：功能清单、领域边界、数据库逻辑模型和核心流程
- [需求变更履历](./docs/需求变更履历.md)：需求与架构决策的可追溯记录
