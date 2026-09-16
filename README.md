# language-lean

自用的语言学习工具。

项目当前已建立日语 Web 版 MVP 的前后端骨架，业务功能逐步实现中。

- frontend：Vue 3 / TypeScript / Ionic Vue / Vite，包含语言配置页面及复习、离线存储接口。
- backend：Java 21 / Spring Boot / PostgreSQL / Flyway，包含语言配置 API 和默认关闭的个人接口边界。
- [项目架构与启动说明](./docs/项目架构.md)：目录、模块职责、本地运行和验证命令。

Docker 本地启动：复制 `.env.example` 为 `.env`，设置数据库密码和首次管理员账号，运行 `docker compose up --build -d`，访问 http://localhost:5173。前端、API、PostgreSQL 全部在容器内运行，宿主机只需 Docker Desktop。当前已支持基础登录和修改初始密码，尚不能进行真实学习或离线同步。

停止使用 `docker compose stop`，恢复使用 `docker compose start`。移除容器及网络使用 `docker compose down`，数据库卷仍保留；如果确定连本地账户和数据也不要了，使用 `docker compose down --volumes`。这些命令在项目根目录执行，仅针对本项目。

本机使用 Ubuntu WSL 内的独立 Docker 引擎，无需启动 Docker Desktop。在 PowerShell 中运行：

```powershell
wsl -d Ubuntu -- bash -lc 'cd /mnt/d/gitHome/language-lean && docker compose up --build -d'
```

停止时将上述命令末尾替换为 `docker compose stop`；查看状态替换为 `docker compose ps`。浏览器仍访问 http://localhost:5173。

- [项目概要](./docs/项目概要.md)：产品目标、范围和技术方向
- [设计文档](./docs/设计文档.md)：功能清单、领域边界、数据库逻辑模型和核心流程
- [需求变更履历](./docs/需求变更履历.md)：需求与架构决策的可追溯记录
