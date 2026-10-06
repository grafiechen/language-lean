# 第一版完整 Figma 设计材料

更新日期：2026-10-07。

[在线完整页面参考](https://www.figma.com/design/Q6nWK9yDNLyEhhJL4a9dte?node-id=3-2)已通过Figma官方网页转设计通道写入。请打开最新的`3:2`节点；早期`1:2`仅为首次捕获参考。线上采用网页转换的原始框架，不等同于下面的原生组件和原型交付。

当前材料覆盖110个页面、状态和弹窗，桌面1440px和移动390px各一套，共220个画板。108个状态对应已有代码，2个明确保留为扩展设计：单词本重命名、公开删除提示。第一版只保留听音回忆，不新增未确认题型、每日配额或个人导出入口。

账号找回和重置、母语译文及缺失回退、假名检索和页码保留、私有内容与公开词条个人覆盖、复习与耳词、多账号离线缓存、已有备份本机恢复、贡献审核、发音反馈、用量和服务器灾备均已纳入。服务器备份默认关闭；设计里的数量、账号和记录全是虚构样例。

- [完整导入包](./language-lean-complete.zip)：原生生成插件、预览、覆盖清单、截图和检查报告。
- [本地完整预览](./complete/preview.html)：浏览器打开并选择页面，可查看桌面与移动版本。
- [页面覆盖清单](./complete/页面覆盖清单.md)：逐项列出页面、实现状态与实际路由。
- [Figma插件清单](./complete/plugin/manifest.json)：用于桌面客户端导入开发插件。
- [验收状态](./complete/Figma验收状态.md)：区分已验证材料、线上页面参考与待验证原生设计。

## 导入原生设计

1. 解压ZIP，保持`complete/plugin/manifest.json`、`code.js`、`ui.html`在同一目录。
2. 使用Figma桌面客户端打开可编辑的Design文件，例如上面的在线文件，先选中空白的Page 1。
3. 在Plugins → Development → Import plugin from manifest中选择清单。
4. 运行 **Language Lean · 第一版完整页面**，点击“生成全部页面”。
5. 生成器优先复用当前空白页，否则创建新页，绝不覆盖非空页面。设计包含基础组件分区和14个页面模块分区。17个基础组件包含按钮、字段、列表项、复选框、状态标签和指标卡；按钮、字段、列表项及复选框有4组变体，文字属性统一，主题变量和原型跳转保留。
6. 保存插件生成报告，检查实际图层、字体、画板和原型。原生设计确认后可手动删除此前网页参考页；插件不会自动覆盖或删除已有设计。
7. 如需`.fig`文件，在Figma中使用Save local copy。**当前ZIP是导入材料，不是已验证的`.fig`副本。**

插件只使用原生TEXT、COMPONENT、COMPONENT_SET和INSTANCE，不能把整页截图放入画板。无网络权限、不读取浏览器登录或业务数据库，已有同名设计页时停止，避免覆盖你的修改。失败可能留下部分内容，请先保留失败页用于排查。

字体优先使用产品的Microsoft YaHei及中文回退字体。在线环境已确认提供Noto Sans SC，生成器已支持；产品CSS也明确保留此回退。缺少支持的中文字体时失败，不默默改用Inter。

## 本轮检查与限制

本地预览的全部220画板通过尺寸、越界和横向溢出检查，无脚本错误；生成32张代表页面截图和14张模块总览。原生生成器模型检查17个组件、4组变体、统一文字属性、原型目标、字体缺失、空白页复用及重复运行保护，报告全部模拟节点ID。

官方网页转换的来源已检查：220个视图，桌面宽1440px、移动宽390px，无内容图片。Figma返回已写入节点`3:2`的完成结果。它提供在线页面参考；不能据此声称原生组件、变量和原型已写入或实际字体/布局已验收。

当前Starter账号的普通MCP画布操作和读回仍受额度限制，所以本轮未直接运行原生生成器，也无法完成实际Figma结构和视觉检查。恢复额度后继续读回并验收；或者在桌面客户端运行插件并保存实际生成报告。参见[Figma官方调用额度说明](https://developers.figma.com/docs/figma-mcp-server/rate-limits-access/)。

## 重新生成

```powershell
node docs/figma/src/build.mjs
node tools/verify-figma-builder.mjs
node tools/verify-figma-design.cjs
```

设计数据在`src/screens.mjs`，原生生成器在`src/figma-builder.mjs`。检查报告保留全部前端源码与设计源文件SHA256，避免代码变化后沿用旧证据。Playwright和Chrome路径可通过`LANGUAGE_LEAN_PLAYWRIGHT_PATH`、`LANGUAGE_LEAN_BROWSER`指定。

`language-lean-v1.svg`和旧ZIP仅是四屏历史草稿，不作为本次完整输出。
