# Jitendex 中文例句补充

2026-10-04继续词典与学习开发，在既有JMdict/Tatoeba精选例句上补充简体中文译文。使用greyindex的Jitendex非官方中文本地化`v2026.08.11-zh.4`（2026-09-27发布），不替换Tomoshi释义，不另建词条或例句身份。

## 固定来源与许可

- 项目：https://github.com/greyindex/jitendex-yomitan-zh
- 发布页：https://github.com/greyindex/jitendex-yomitan-zh/releases/tag/v2026.08.11-zh.4
- 下载：https://github.com/greyindex/jitendex-yomitan-zh/releases/download/v2026.08.11-zh.4/jitendex-yomitan-zh-full-review-dedup-20260927.zip
- ZIP SHA-256：`9872fc32d05e3f7ea9b5b70a253610e49d99b6eef7db519b96faad1bbec2b678`
- 待核清单：https://github.com/greyindex/jitendex-yomitan-zh/blob/64409f0/reports/2026-09-27/uncertain.csv
- 清单 SHA-256：`f17391c9ad9db1226fbea7cc771ff9a27d6f0c2c8cb46ee72f213961cb814a5c`

保存固定提交的LICENSE、NOTICE.md及待核CSV；ZIP按已有Git规则忽略，生成SQL与校验清单留在项目中。

原词典由Stephen Kraus维护，JMdict来源为EDRDG；日英例句来自Tatoeba，原句许可及作者继续保存在例句attribution（CC BY 2.0 FR）。中文社区本地化来自greyindex，经模型翻译和模型校订，未全部人工核验，衍生词典和本项目转换的中文数据按CC BY-SA 4.0提供。每份译文单独保存来源、许可、发布地址及署名；页面标注“机器翻译（模型校订）”，不将机器译文标成Tatoeba原生中文。

本项目修改：去除ruby注音及出处脚注的排版标记，精确匹配既有例句，转换为zh-Hans译文映射；拒绝无法还原字形图片的中文。原出处脚注指向的Tatoeba链接由既有例句出处保留。没有导入其它释义、词频、图片或未经核验的繁体译文。

## 匹配和保护

必须满足相同JMdict ID、当前主要写法、允许的读音、相同Tatoeba句子ID及完全相同的日文原句。交叉引用和写法表不作为当前词条例句；重复变体译文一致时去重，不同译文冲突时跳过。

固定待核清单涉及2,508个词条ID，本次排除其中105个有待补例句的身份。已有任何简体、繁体或未区分字形的中文都保留，避免新机器译文遮蔽Tatoeba原生中文。

SQL仅对无草稿、已发布、日语开源身份，且当前为已知例句或Tomoshi种子版本的词条追加历史；封禁、手工来源、人工发布及恢复版本跳过。重复执行不新增，原历史不更新。原英文、日文、发音、例句ID和所有词义（含Tomoshi译文）保留，学习和私人内容表不参与更新。

## 部署与重新生成

先完成Flyway至少V16，核对各生成清单的SQL SHA-256，再按顺序执行：

```bash
gzip -dc data/jmdict/generated/jmdict-initial.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
gzip -dc data/jmdict/generated/jmdict-examples.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
gzip -dc data/tomoshi/generated/tomoshi-translations.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
gzip -dc data/jitendex/generated/jitendex-examples.sql.gz | psql --set ON_ERROR_STOP=on "$DATABASE_URL"
```

保存种子供后续任意部署地点使用；常规业务编辑继续通过JPA、草稿发布及贡献审核。

重新生成使用Python标准库，无需宿主依赖安装：

```bash
python3 tools/jitendex_examples.py \
  data/jitendex/source/jitendex-yomitan-zh-full-review-dedup-20260927.zip \
  data/jitendex/source/uncertain.csv \
  data/jmdict/generated/jmdict-initial.sql.gz \
  data/jmdict/generated/jmdict-examples.sql.gz \
  data/tomoshi/generated/tomoshi-translations.sql.gz \
  data/jitendex/generated/jitendex-examples.sql.gz \
  --manifest data/jitendex/generated/jitendex-examples.manifest.json
python3 -m unittest discover -s tools -p test_jitendex_examples.py -v
```

生成器核对固定ZIP、CRC、格式、语言、许可和待核CSV；只解析本项目种子的已知版本ID，不执行输入SQL。切换来源版本需重新检查对应关系。

部署后可执行`psql --set ON_ERROR_STOP=on "$DATABASE_URL" -f tools/check_jitendex_import.sql`，比较新增版本与直接前驱，检查原正文、已有译文、出处和历史连续性并输出覆盖统计。该脚本只读业务表，临时核对表随连接释放。

## 覆盖与修订

生成26,990个词条的29,691条例句中文译文，已有1,245条中文保留。全部符合种子保护条件时，30,936条已有例句有中文（约98.1%，共31,527条引用）；这是覆盖率，不是语义准确率，也不表示全词库都有例句。

剩余591条继续英语回退：待核词条105条、译文冲突157条、版本差异或无法匹配329条。后续可通过后台编辑发布或投稿审核修订。来源只提供本次核验的简体中文，繁体母语继续使用已有通用中文或英语，不将简体冒充繁体。

简体母语下刷新详情可见译文，发音保持日语。旧离线快照需联网重新准备单词本，已完成及待同步的答题不重置。原句和中文译文的来源、许可分别显示。

验收：13项Python边界测试通过；独立PostgreSQL重放完整四步部署并设置4条保护用例，实际追加26,986条，重复执行0新增；所有更新词条的原正文、英文、Tomoshi译文、例句和原译文比对通过，历史连续。Chrome页面验证中文例句、来源与独立许可、英文切换、繁体母语回退、去除脚注及390px无溢出或运行时错误，未调用云音频。

本地5174预览实际追加26,990个版本，补充29,691条例句译文；词条身份总数208,684与例句总数31,527保持不变。账户、角色、登录标识、单词本、学习关联、复习事件、个人覆盖和私有词条的前后内容校验值一致；原正文、所有Tomoshi译文、原例句和已有译文通过前驱版本全量比较。导入前完整备份保存在忽略目录`.cache/before-jitendex-examples-20261004.sql.gz`，不提交账户数据。
