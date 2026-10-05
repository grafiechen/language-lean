# JMdict 初始词典

第一版基准词典采用 EDRDG 维护的 JMdict 基础版 `JMdict_b`。该版本包含日语词条和英语释义，并排除从 JMnedict 合入的约 7000 条人名。

- 项目说明：https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html
- 官方下载：https://www.edrdg.org/pub/Nihongo/JMdict_b.gz
- 许可说明：https://www.edrdg.org/edrdg/licence.html
- 许可：Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）

官方文件每日生成。许可要求产品标注数据来源和许可，并建立定期更新流程。词条详情页会显示 JMdict 来源和许可链接。

## 生成初始化 SQL

在项目根目录执行：

```bash
python3 tools/jmdict_to_postgres.py \
  data/jmdict/source/JMdict_b.gz \
  data/jmdict/generated/jmdict-initial.sql.gz \
  --manifest data/jmdict/generated/jmdict-initial.manifest.json
```

生成过程采用流式解析，选择每个 JMdict 条目的主要书写形式，并按 `re_restr`、`stagk`、`stagr` 过滤对应读音和词义。当前业务模型还没有“同一词条的多个书写变体”，因此其他书写形式暂不展开为独立词条；清单会记录源条目数、实际生成数和跳过数。

稳定 UUID 由 JMdict `ent_seq` 生成。重复执行同一份 SQL 不覆盖人工词条或已有发布版本。

## 在新环境安装

先启动应用并让 Flyway 完成全部结构迁移（至少 V16，V15 提供读音检索函数与索引），再执行：

```bash
gzip -dc data/jmdict/generated/jmdict-initial.sql.gz | \
  psql --set ON_ERROR_STOP=on "$DATABASE_URL"
```

SQL 使用 PostgreSQL `COPY` 暂存后再幂等写入正式表，部署位置不影响数据内容。执行前应核对清单中的两个 SHA-256。

例句补充源文件为官方`JMdict_e_examp.gz`，生成和执行步骤见`docs/母语译文与例句导入.md`。基准初始化后执行保存的`generated/jmdict-examples.sql.gz`，追加独立历史版本，不覆盖人工修订。例句许可单独记录为CC BY 2.0 FR，不沿用词典的CC BY-SA 4.0。

例句补充之后可执行`data/tomoshi/generated/tomoshi-translations.sql.gz`，在同一稳定词条上追加简繁中文释义版本。来源、许可、严格匹配规则及人工修订流程见[Tomoshi数据说明](../tomoshi/README.md)。原基准与例句种子文件保持不变。

最后执行`data/jitendex/generated/jitendex-examples.sql.gz`可为既有日文例句补充简体中文，不覆盖已有Tatoeba中文，也不修改Tomoshi释义。来源、许可及完整部署顺序见[Jitendex例句说明](../jitendex/README.md)。
