# language-lean

自用的语言学习工具。

项目当前已建立日语 Web 版 MVP 的前后端骨架，业务功能逐步实现中。

- frontend：Vue 3 / TypeScript / Ionic Vue / Vite，包含账户、词典管理、投稿审核、私人内容、单词本、听音训练和多账号离线缓存。
- backend：Java 21 / Spring Boot / PostgreSQL / Flyway / JPA，包含认证、版本化词典、开源导入、贡献审核、私人内容、FSRS复习同步和音频 API。
- [公共发音反馈与处理](./docs/公共发音反馈与测试.md)：词条和例句的问题反馈、后台处理、新音频校验与账号隔离。
- [项目架构与启动说明](./docs/项目架构.md)：目录、模块职责、本地运行和验证命令。

Docker 本地预览：复制 `.env.example` 为 `.env`，设置数据库密码和首次管理员账号，运行 `docker compose up --build -d`，访问 http://localhost:5173。若本机 5173 已被其他项目占用，可在 `.env` 设置 `WEB_PORT=5174` 后访问 http://localhost:5174。前端、API、PostgreSQL 全部在容器内运行；本机使用 Ubuntu WSL 中的 Docker Engine，无需 Docker Desktop。

如果本机的 WSL 在命令结束后会自动关闭，导致容器随之停止，请去掉 `-d` 并保持该终端窗口运行：`wsl -d Ubuntu -- bash -lc 'cd /mnt/d/gitHome/language-lean && docker compose up'`。

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

管理员登录后从首页进入“后台管理”（/admin）：账号页提供列表、新增弹窗、启用、禁用及确认删除，创建时邮件发送初始密码。手工录入可按“新增词条 → 保存草稿 → 确认并发布”，批量导入可在“批量导入”页上传 JSON、检查重复与错误、确认发布。“系统字典”页维护内容来源等下拉选项，“贡献审核”页处理用户明确提交的快照。用户在“浏览基础词典”（/dictionary）查看已发布内容。后台支持多读音、多词义、例句、来源与许可证、语言配置、导入批次、历史恢复草稿和封禁；仓库已保存 JMdict 基础版原始文件、转换工具和可重复执行的 PostgreSQL 初始化 SQL。TTS 配置、音频生成和听音训练已接入，需要服务端配置云凭据；邮件找回使用一次性重置链接，正式 SMTP 需配置。私人投稿与公开修订审核、本人注销及管理员删除已完成；注销清理个人数据和所属缓存，保留公开词典和许可证，同名重建从零开始。学习身份自动合并仍待实现。

- [词典导入格式与实际测试](./docs/词典导入格式.md)：示例文件、操作步骤、重复提示和规范化 JSON 字段
- [复习调度、进度协议与测试](./docs/复习进度协议与测试.md)：FSRS、逐词提交、离线补传、版本隔离和当前实现范围
- [音频配置与听音训练](./docs/音频配置与听音训练.md)：Google/R2 服务端配置、音频补生成、听音训练和当前离线范围
- [离线准备与多账号缓存](./docs/离线准备与多账号测试.md)：单词本下载、断网重新打开、会话过期恢复和上传隔离
- [多设备同步与冲突处理](./docs/多设备同步与冲突处理.md)：远程删除/重置核对、冲突重试、明确丢弃及依赖链清理
- [离线 FSRS 调度与验证](./docs/离线FSRS调度与验证.md)：离线到期、连续轮次、本地预估与服务端确认、跨语言一致性测试
- [离线缓存管理与测试](./docs/离线缓存管理与测试.md)：缓存用量、下载清理、学习记录保护和空间不足处理
- [个人内容覆盖与测试](./docs/个人内容覆盖与测试.md)：个人释义、笔记与标签、内容冲突、离线答案和共享进度保护
- [私有词条与个人音频测试](./docs/私有词条与个人音频测试.md)：列表弹窗录入、个人 TTS、离线训练及彻底删除
- [个人读音与例句覆盖测试](./docs/个人读音与例句覆盖测试.md)：公开词条的个人读音/例句、分别继承、音频版本和离线资源更新
- [词条投稿与审核测试](./docs/词条投稿与审核测试.md)：本人投稿、冻结快照、新词/补充/修订审核、并发版本和来源删除
- [MOJi背词模块调研](./docs/MOJi背词模块调研.md)：官方资料、题型参考及示例交互草图
- [背词界面改版与测试](./docs/背词界面改版与测试.md)：实际背词总览、单词本筛选、听音卡片与离线恢复验收
- [JMdict 初始词典](./data/jmdict/README.md)：官方来源、许可、生成清单和 PostgreSQL 初始化方法
- [母语译文与例句导入](./docs/母语译文与例句导入.md)：读音搜索、母语设置、可追溯例句及保存的补充SQL
- [个人CSV导入与测试](./docs/个人CSV导入与测试.md)：模板、预检、已存在提示、个人覆盖与跨本进度保护
- [耳词训练与学习备份](./docs/耳词训练与学习备份.md)：多读音轮换、离线资源保护、全历史及本机JSON导出
- [学习备份恢复与测试](./docs/学习备份恢复与测试.md)：同账号本机答题/草稿恢复、预检、已上传去重和删除/重置隔离
- [账号管理与邮件找回](./docs/账号管理与邮件找回测试.md)：账号列表、新增弹窗、启用/禁用、邮件初始密码、一次性找回链接和本地收件箱
- [账号注销与数据清理](./docs/账号注销与数据清理测试.md)：本人/管理员确认删除、私人历史及音频回收、多账号缓存隔离和同名重建
- [Figma 第一版设计材料](./docs/figma/README.md)：93 个页面、状态与弹窗的桌面／移动设计预览及原生节点生成插件；实际 Figma 导入与视觉验收尚未完成。可下载 [完整材料 ZIP](./docs/figma/language-lean-complete.zip)。

- [Cloudflare Workers 前端部署](./docs/Cloudflare-Workers部署.md)：生产分支、构建命令、独立 API 地址与跨域登录配置
- [项目概要](./docs/项目概要.md)：产品目标、范围和技术方向
- [设计文档](./docs/设计文档.md)：功能清单、领域边界、数据库逻辑模型和核心流程
- [需求变更履历](./docs/需求变更履历.md)：需求与架构决策的可追溯记录
