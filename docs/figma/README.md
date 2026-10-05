# 第一版 Figma 设计材料

当前状态：**完整页面的本地生成材料已准备，实际 Figma 导入和视觉验收尚未完成。** 在线目标文件为 [Language Lean · 第一版前端界面](https://www.figma.com/design/BGo4OHWs89pOmKYOuioEtq)，本次只读检查被 Starter MCP 调用额度拦截。不能据此宣称在线文件已有完整设计。

当前材料覆盖 93 个页面、状态与弹窗，桌面 1440px 和移动 390px 各一套，共 186 个画板。包含账户、单词本、基础词典、词条编辑及历史、词典导入、语言与系统字典配置、个人内容、贡献审核、听音复习和耳词、音频异常、离线准备、同步、备份和其他后台。44 个状态对应已有前端界面，49 个是第一版待开发设计；该标识不代表本次验证了业务功能。

- [本地完整预览](./complete/preview.html)：双击用浏览器打开，可按页面切换查看。
- [页面覆盖清单](./complete/页面覆盖清单.md)：逐项列出实现状态和当前路由。
- [Figma 开发插件清单](./complete/plugin/manifest.json)：导入开发插件所需文件。
- [完整材料 ZIP](./language-lean-complete.zip)：包含插件、预览、覆盖清单和本地检查报告。

## 导入 Figma

1. 解压 ZIP，保持 `complete/plugin/manifest.json`、`code.js`、`ui.html` 在同一目录。
2. 使用 Figma 桌面客户端打开一个你可编辑的 Design 文件。画板较多，推荐使用独立草稿文件。
3. 在插件的开发菜单中选择导入插件清单（Import plugin from manifest），选择 `complete/plugin/manifest.json`。
4. 运行 **Language Lean · 第一版完整页面**，点击“生成全部页面”。
5. 插件使用原生 `TEXT`、`COMPONENT`、`INSTANCE`、自动布局和主题变量创建新页面。文本可从图层或组件文字属性编辑；颜色、间距通过本地变量调整。没有整页截图填充，不需要将 SVG 文字转回文本。
6. 生成成功后保存插件报告。报告读回实际 Figma 中全部画板和可见节点的真实尺寸，越界会明确报错；它仍不能代替视觉检查。检查各模块的桌面与移动画板，确认文字、表格和弹窗没有裁切或重叠。
7. 在 Figma 中选择保存本地副本（Save local copy）得到 `.fig` 文件。**仓库目前没有已验证的 `.fig` 文件。**

插件无网络权限，只使用虚构样例，不读取本地登录信息或业务数据库。字体优先使用源界面的中文字体 Microsoft YaHei；缺失时使用系统中文字体 PingFang SC 或 Noto Sans CJK SC。没有兼容字体时明确失败，不自动使用 Inter。字体和渲染仍需在实际 Figma 文件中确认。

插件生成单个设计页面，内含 13 个模块分区及一个基础组件分区。已有同名页时停止，不覆盖你的修改；如需重新生成，请先重命名旧页。中途失败可能留下部分生成内容，同样先重命名该页再重试。

## 范围与验证边界

页面是依据当前代码结构、样式和第一版文档整理的设计表达。当前代码里的浏览器原生确认框用应用内弹窗表达；部分操作状态单独拆为画板。待开发页面不是当前运行软件截图。仍需导入后与实际页面对照，检查精确间距、字体、控件状态和样例内容长度。

第一版只设计听音回忆；多题型扩展契约保留在说明中，没有加入选择题、拼写题、变形训练、原生 App 或复杂统计页面。CSV 字段、备份合并等文档尚未确定的细节保留说明，没有新增未经确认的策略。

本地检查已完成：

- 93 个页面及 186 个预览画板的水平溢出与画板越界检查；浏览器无脚本错误。
- 13 个模块的预览图，以及 12 个代表页面的桌面／移动截图。
- 生成器在本地模型中生成 186 个画板、16 个基础组件；检查字体缺失、重复运行、原型目标、自动布局轴和完整节点 ID 报告。
- [预览检查报告](./complete/audit/local-preview-report.json) 和 [生成器模型报告](./complete/audit/builder-model-report.json) 均明确保留实际 Figma 验收未完成。

本地模型不能证明 Figma API 运行成功；HTML 预览不能证明实际 Figma 布局正确。完成交付还需要实际生成报告、原生图层检查、Figma 全屏视觉对照和 `.fig` 本地副本。

## 重新生成

在项目根目录执行：

```powershell
node docs/figma/src/build.mjs
node tools/verify-figma-builder.mjs
node tools/verify-figma-design.cjs
```

浏览器检查使用本机 Codex runtime 的 Playwright。换环境时通过 `LANGUAGE_LEAN_PLAYWRIGHT_PATH` 指定 Playwright 模块路径、`LANGUAGE_LEAN_BROWSER` 指定 Chrome 路径。页面数据位于 `src/screens.mjs`，原生生成器位于 `src/figma-builder.mjs`，生成产物位于 `complete/`。

## 旧草稿

`language-lean-v1.svg` 和 `language-lean-v1.zip` 仅有四屏示意图，保留作历史草稿。它们不是完整 Figma 交付，也没有验证导入后的文本可编辑性。
