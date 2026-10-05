#!/usr/bin/env bash
# 仅下载官方文本元数据，无音频、无账号登录；大文件保留在忽略目录。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data/tatoeba/source
for language in jpn eng cmn; do
  curl --fail --location --retry 2 "https://downloads.tatoeba.org/exports/per_language/${language}/${language}_sentences_detailed.tsv.bz2" \
    -o "data/tatoeba/source/${language}_sentences_detailed.tsv.bz2"
done
for file in jpn_indices links; do
  curl --fail --location --retry 2 "https://downloads.tatoeba.org/exports/${file}.tar.bz2" -o "data/tatoeba/source/${file}.tar.bz2"
done
python3 tools/tatoeba_example_metadata.py data/jmdict/source/JMdict_e_examp.gz data/tatoeba/source data/tatoeba/generated/example-metadata.json
