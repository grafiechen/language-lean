# Tomoshi 中文释义增量数据

2026-10-04 用户确认先采用 Tomoshi，后续可人工修订。使用固定发布版本 `2026-09-02`，在现有 JMdict 身份上补充简体、繁体中文释义，不另建一本重复词库。

- 项目：https://github.com/tomoshi-app/tomoshi-dict-data
- 固定发布页：https://github.com/tomoshi-app/tomoshi-dict-data/releases/tag/v2026-09-02
- 压缩数据库：https://github.com/tomoshi-app/tomoshi-dict-data/releases/download/v2026-09-02/tomoshi-dict-open.db.zst
- 压缩 SHA-256：`7153dfd7a8e42e2d920308370eac90cf9f2e4b4cfe67fb9a86e9aa1c89494073`
- 解压 SQLite SHA-256：`8b19c7d65a7d7d6df9afc58832b17b22fd349724e5d06d2acf3bb9a6c4b0ed9d`

完整数据库约650MB，原始压缩包约86MB，下载文件不纳入 Git；固定下载地址、校验值、该发布提交的 `LICENSE.md` / `NOTICE.md` / `UPSTREAM-README.md` 和生成 SQL 留在项目中。

## 来源、许可与修改说明

仅读取 `entries`、`zh_defs`、`zh_defs_zhtw`，在数据库 `table_licenses` 核实这些表均采用 CC BY-SA 4.0。JMdict 原文版权归 EDRDG，中文衍生释义由 Y1Z 提供；保留双方署名，不表示其对本项目的认可。

中文为模型辅助翻译并经另一模型校订，未全部人工核验。每份译文保存 Y1Z、固定发布地址、CC BY-SA 4.0 和“机器翻译（模型校订）”标注。词条详情已有的译文来源展示会显示这些信息。本项目的衍生译文数据按 CC BY-SA 4.0 提供。

本项目修改：按 JMdict ID、原始义项下标、英文释义及词性精确匹配，将中文转换为 `zh-Hans` / `zh-Hant` 译文映射；过滤缺失、损坏、超长和未译文本；仅追加发布历史。没有导入其他辨析、汉字笔顺或 JLPT 数据。虽然中文表可能残留孤立例句译文，本次不使用这些字段：开放包缺少可核对的日文原句和词义关联，不能据下标猜配现有例句。

## 在新环境部署

先让应用完成 Flyway 至少 V16，再依次执行：

```bash
gzip -dc data/jmdict/generated/jmdict-initial.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
gzip -dc data/jmdict/generated/jmdict-examples.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
gzip -dc data/tomoshi/generated/tomoshi-translations.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
```

执行前核对各生成清单的 SQL SHA-256。中文补充应在例句补充之后执行，因为两种补充都只接受已知的原始导入版本。

Tomoshi之后可执行`data/jitendex/generated/jitendex-examples.sql.gz`，补充既有例句的简体中文译文，保留本节的所有释义与身份。完整四步部署顺序及边界见[Jitendex例句补充](../jitendex/README.md)。

词条、读音和义项 UUID、英文释义、现有例句及所有学习表保持原值。仅对已发布的日语开源词条、无后台草稿、当前版本仍是基准或已知例句种子的身份追加中文版本；手工词条、封禁、人工发布或恢复的版本均跳过。历史正文不更新，重复执行不再新增。已有译文映射优先保留。

## 重新生成

下载固定压缩包至 `data/tomoshi/source/tomoshi-dict-open.db.zst`，使用 zstd 解压到 `.cache/tomoshi-dict-open.db`。解压可在临时 Docker 容器中进行，避免给宿主机安装工具。按上述两个 SHA-256 检查文件后执行：

```bash
python3 tools/tomoshi_to_postgres.py \
  .cache/tomoshi-dict-open.db data/tomoshi/source/tomoshi-dict-open.db.zst \
  data/jmdict/source/JMdict_b.gz data/jmdict/generated/jmdict-examples.sql.gz \
  data/tomoshi/generated/tomoshi-translations.sql.gz \
  --manifest data/tomoshi/generated/tomoshi-translations.manifest.json
python3 -m unittest discover -s tools -p test_tomoshi_to_postgres.py -v
```

生成器使用 Python 标准库，数据库只读打开；校验固定文件与逐表许可，拒绝未知发布包。切换 Tomoshi 或 JMdict 版本需要重新评估对应关系，不可仅改日期绕过校验。

## 覆盖与人工修订

当前基准208,684个词条，生成206,826个可补充词条（约99.1%）；238,780个义项有简体译文，238,783个有繁体译文。缺失来源或版本变化等原因导致1,858个词条未生成补充，部分已补充词条也可能仍有英文义项；清单分别统计缺失和错位义项。保持英文回退，不承诺全部语义正确。

管理员可在现有词条编辑器修改对应中文译文，保存草稿后发布，历史保留导入版用于回退。普通用户仍可编辑个人内容、提交修订申请，审核前不改变公开译文。以后重跑此种子不会覆盖人工发布。

修改母语后刷新词条即可看到对应简繁译文。离线下载的旧快照需要联网重新准备单词本内容，原进度和待上传答题保留。词条与例句发音保持日语，导入不触发TTS或云访问。

验收：9项 Python 边界测试通过；真实 PostgreSQL 安装基准及例句后导入206,822条（4条人为保护用例正确跳过），重跑新增0条；全库读音、英文、义项顺序和31,527条例句保留，历史连续。现有页面的真实 Chrome 验收通过简体、繁体、英文切换、来源展示和390px无溢出，未调用云音频。

本地5174预览实际应用206,826条，新增词条0条，词典身份总数仍为208,684；账户、账号角色及登录标识、单词本、学习关联、复习事件、个人覆盖与私有词条的导入前后内容校验值全部一致。旧正文和31,527条例句通过全量比较，API健康为UP；验收容器与独立数据库已清理。仅本机的完整备份保存在忽略目录`.cache/before-tomoshi-import-20261004.sql.gz`，不将账户数据提交到Git。
