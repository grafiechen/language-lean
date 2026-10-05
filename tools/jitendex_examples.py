#!/usr/bin/env python3
"""按现有 Tatoeba 原句精确补充 Jitendex 简体中文例句译文，生成线性版本 SQL。"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import unicodedata
import uuid
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

from jmdict_to_postgres import SYSTEM_ACTOR, stable_id
from tomoshi_to_postgres import sha256

VERSION = '2026.08.11-zh.4'
PROJECT_URL = 'https://github.com/greyindex/jitendex-yomitan-zh'
RELEASE_URL = PROJECT_URL + '/releases/tag/v' + VERSION
FILENAME = 'jitendex-yomitan-zh-full-review-dedup-20260927.zip'
SOURCE_URL = PROJECT_URL + '/releases/download/v' + VERSION + '/' + FILENAME
SOURCE_SHA256 = '9872fc32d05e3f7ea9b5b70a253610e49d99b6eef7db519b96faad1bbec2b678'
UNCERTAIN_SHA256 = 'f17391c9ad9db1226fbea7cc771ff9a27d6f0c2c8cb46ee72f213961cb814a5c'
LICENSE = 'CC BY-SA 4.0'
SOURCE_NAME = 'Jitendex 中文版（greyindex）2026-09-27 · 机器翻译（模型校订）'


def seed_rows(path: Path, table: str):
    """流式读取本项目生成的 COPY CSV 段，不执行其中的 SQL。"""
    csv.field_size_limit(20 * 1024 * 1024)
    with gzip.open(path, 'rt', encoding='utf-8', newline='') as stream:
        for line in stream:
            if line.startswith('COPY ' + table + ' '):
                break
        else:
            raise ValueError(f'{path} 缺少 {table} COPY 段')
        for row in csv.reader(stream):
            if row == ['\\.']:
                return
            yield row
        raise ValueError(f'{path} 缺少 COPY 结束标记')


def nodes(value, excluded=frozenset()):
    """只遍历结构化内容；引用、写法列表与署名不作为当前词义的例句。"""
    if isinstance(value, list):
        for child in value:
            yield from nodes(child, excluded)
    elif isinstance(value, dict):
        if value.get('data', {}).get('content') in excluded:
            return
        yield value
        yield from nodes(value.get('content'), excluded)


def plain_text(value) -> str:
    """提取正文时去掉 ruby 注音，不将 HTML 或外部资源送进页面。"""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return ''.join(plain_text(child) for child in value)
    if isinstance(value, dict):
        if value.get('tag') in {'rt', 'rp', 'img'} or value.get('data', {}).get('content') == 'attribution-footnote':
            return ''
        return plain_text(value.get('content'))
    return ''


def valid_chinese(text: str) -> bool:
    """拒绝空白、乱码、未译英语和超长文本，不以截断方式掩盖错误。"""
    return (bool(text.strip()) and len(text.encode('utf-16-le')) // 2 <= 2000
            and '\ufffd' not in text and any('\u3400' <= c <= '\u9fff' for c in text)
            and not any(unicodedata.category(c) in {'Cc', 'Cs'} and c not in '\n\t' for c in text))


def row_examples(row: list) -> list[tuple[str, str, str]]:
    """返回 Tatoeba ID、日文正文、中文译文；只有明确日中语言标注才接受。"""
    result = []
    for definition in row[5]:
        if not isinstance(definition, dict) or definition.get('type') != 'structured-content':
            continue
        for node in nodes(definition.get('content'), {'xref', 'forms', 'attribution'}):
            data = node.get('data', {})
            if data.get('content') != 'example-sentence' or data.get('source-type') != 'tat':
                continue
            source_id = str(data.get('source', ''))
            if not source_id.isdigit():
                continue
            body = list(nodes(node.get('content')))
            japanese = [value for value in body if value.get('data', {}).get('content') == 'example-sentence-a']
            chinese = [value for value in body if value.get('data', {}).get('content') == 'example-sentence-b']
            if len(japanese) != 1 or len(chinese) != 1:
                continue
            if not any(value.get('lang') == 'ja' for value in nodes(japanese[0])):
                continue
            if not any(value.get('lang') in {'zh', 'zh-CN', 'zh-Hans'} for value in nodes(chinese[0])):
                continue
            # 不能把有字形图片的译文静默变成缺字文本；出处由已有例句链接单独保存。
            if any(value.get('tag') == 'img' for value in nodes(chinese[0])):
                continue
            jp = plain_text(japanese[0]).strip(); zh = plain_text(chinese[0]).strip()
            if jp and valid_chinese(zh):
                result.append((source_id, jp, zh))
    return result


def eligible_examples(content: dict, counters: Counter) -> dict:
    """仅补已有的英文例句；保留 Tatoeba 原生中文和任何已填写的中文译文。"""
    result = {}
    for sense in content['senses']:
        for example in sense['examples']:
            attribution = example.get('attribution') or {}
            source_id = str(attribution.get('sourceId', ''))
            translations = example.get('translations') or {}
            if any(language in translations for language in {'zh', 'zh-Hans', 'zh-Hant'}):
                counters['existingChinesePreserved'] += 1
                continue
            if example.get('translationLanguage') != 'en' or not example.get('translation'):
                counters['nonEnglishSourceSkipped'] += 1
                continue
            if not source_id.isdigit() or attribution.get('sourceUrl') != 'https://tatoeba.org/en/sentences/show/' + source_id:
                counters['unverifiedSourceSkipped'] += 1
                continue
            result[example['id']] = (source_id, example['text'])
    return result


def match_translations(expected: dict, candidates: dict, counters: Counter) -> dict:
    """同一原句有多个不同译文时跳过，不按变体顺序或得分擅自选择。"""
    patch = {}
    for identifier, key in expected.items():
        values = candidates.get(key, set())
        if len(values) != 1:
            counters['conflictingTranslationSkipped' if values else 'unmatchedExampleSkipped'] += 1
            continue
        patch[identifier] = {'zh-Hans': {'text': next(iter(values)), 'sourceName': SOURCE_NAME,
            'license': LICENSE, 'sourceUrl': RELEASE_URL, 'author': 'greyindex；原词典 Stephen Kraus'}}
        counters['translatedExamples'] += 1
    return patch


def sql_footer(entry_count: int) -> str:
    """只追加已知例句版或 Tomoshi 版，原词义、例句正文、学习数据均不修改。"""
    release = stable_id('release', VERSION, value='JITENDEX_ZH_EXAMPLES:' + SOURCE_SHA256)
    option = stable_id('source-option', 'JITENDEX_ZH')
    return f"""
LOCK TABLE dictionary_entry IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE eligible_jitendex ON COMMIT DROP AS
SELECT seed.entry_id, seed.revision_id, entry.current_revision + 1 AS next_revision,
jsonb_set(current.content::jsonb, '{{senses}}', (
 SELECT jsonb_agg(jsonb_set(sense.value, '{{examples}}', (
   SELECT coalesce(jsonb_agg(CASE WHEN seed.patch::jsonb ? (example.value->>'id') THEN
     jsonb_set(example.value, '{{translations}}', (seed.patch::jsonb->(example.value->>'id')) ||
       coalesce(example.value->'translations', '{{}}'::jsonb))
     ELSE example.value END ORDER BY example.n), '[]'::jsonb)
   FROM jsonb_array_elements(sense.value->'examples') WITH ORDINALITY example(value,n)
 )) ORDER BY sense.n)
 FROM jsonb_array_elements(current.content::jsonb->'senses') WITH ORDINALITY sense(value,n)
))::text AS content
FROM jitendex_example_seed seed JOIN dictionary_entry entry ON entry.id=seed.entry_id
JOIN dictionary_revision current ON current.entry_id=entry.id AND current.revision_number=entry.current_revision
WHERE entry.language_code='ja' AND entry.origin_type='OPEN_SOURCE' AND entry.status='PUBLISHED'
AND entry.draft_content IS NULL AND current.id=ANY(seed.allowed_revisions)
AND NOT EXISTS (SELECT 1 FROM dictionary_revision old WHERE old.id=seed.revision_id);
INSERT INTO dictionary_revision(id,entry_id,revision_number,content,published_by,published_at,note)
SELECT revision_id,entry_id,next_revision,content,'{SYSTEM_ACTOR}',now(),
'Jitendex 中文版 2026-09-27 例句译文；原句精确匹配；机器翻译经模型校订；CC BY-SA 4.0'
FROM eligible_jitendex;
UPDATE dictionary_entry entry SET current_revision=seed.next_revision,
published_readings_search=dictionary_readings_search(seed.content),updated_at=now(),
updated_by='{SYSTEM_ACTOR}',version=version+1 FROM eligible_jitendex seed WHERE entry.id=seed.entry_id;
INSERT INTO system_dictionary_item(id,dictionary_code,item_value,display_name,description,sort_order,enabled,version)
VALUES ('{option}','CONTENT_SOURCE','Jitendex 中文版','Jitendex 中文版',
'greyindex 社区中文本地化；机器翻译经模型校订；原词典 Stephen Kraus；CC BY-SA 4.0。',40,true,0)
ON CONFLICT(dictionary_code,item_value) DO NOTHING;
INSERT INTO dictionary_source_release(id,source_code,source_version,source_url,source_sha256,license_name,entry_count,imported_at)
VALUES ('{release}','JITENDEX_ZH_EXAMPLES','{VERSION}','{SOURCE_URL}','{SOURCE_SHA256}','{LICENSE}',{entry_count},now())
ON CONFLICT(source_code,source_version,source_sha256) DO NOTHING;
SELECT (SELECT count(*) FROM jitendex_example_seed) AS prepared_entries,count(*) AS applied_entries FROM eligible_jitendex;
COMMIT;
"""


def generate(source: Path, uncertain: Path, baseline_sql: Path, examples_sql: Path,
             tomoshi_sql: Path, output: Path, manifest: Path) -> dict:
    """固定源版本并匹配既有正文，输出可重放的增量种子和覆盖清单。"""
    if sha256(source) != SOURCE_SHA256 or sha256(uncertain) != UNCERTAIN_SHA256:
        raise ValueError('固定发布包或待核清单 SHA-256 不一致')
    with uncertain.open(encoding='utf-8-sig', newline='') as f:
        excluded = {row['jmdict_id'] for row in csv.DictReader(f)}
    counters = Counter(); examples = {}; wanted = {}; written = {}; allowed = {}
    for row in seed_rows(examples_sql, 'example_seed'):
        entry_id, revision_id, _, content_json = row
        if str(uuid.uuid5(uuid.UUID(entry_id), 'examples:' + hashlib.sha256(content_json.encode()).hexdigest())) != revision_id:
            raise ValueError('例句版本 ID 不符合生成正文')
        content = json.loads(content_json)
        examples[entry_id] = content
        wanted[entry_id] = eligible_examples(content, counters)
        allowed[entry_id] = [revision_id]
    for row in seed_rows(baseline_sql, 'jmdict_entry_seed'):
        if row[0] in examples:
            written[row[0]] = row[1]
    for row in seed_rows(tomoshi_sql, 'tomoshi_seed'):
        if row[0] in examples:
            expected_revision = uuid.uuid5(uuid.UUID(row[0]), 'tomoshi:2026-09-02:' + hashlib.sha256(row[3].encode()).hexdigest())
            if str(expected_revision) != row[1]:
                raise ValueError('Tomoshi 版本 ID 不符合补充内容')
            allowed[row[0]].append(row[1])
    candidates = defaultdict(lambda: defaultdict(set)); checked_ids = set()
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise ValueError('ZIP CRC 检查失败')
        index = json.loads(archive.read('index.json'))
        if index.get('format') != 3 or index.get('sourceLanguage') != 'ja' or index.get('targetLanguage') != 'zh' or 'CC BY-SA 4.0' not in index.get('attribution', ''):
            raise ValueError('词典结构、语言或来源许可不符合固定发布')
        for name in sorted(archive.namelist()):
            if not name.startswith('term_bank_') or not name.endswith('.json'):
                continue
            for row in json.loads(archive.read(name)):
                if not isinstance(row, list) or len(row) != 8:
                    raise ValueError('Yomitan 行结构错误')
                sequence = str(row[6]); entry_id = str(stable_id('entry', sequence))
                if entry_id not in wanted or not wanted[entry_id]:
                    continue
                if sequence in excluded:
                    checked_ids.add(entry_id)
                    continue
                readings = {reading['reading'] for reading in examples[entry_id]['readings']}
                if row[0] != written.get(entry_id) or row[1] not in readings:
                    continue
                expected = set(wanted[entry_id].values())
                for source_id, japanese, chinese in row_examples(row):
                    if (source_id, japanese) in expected:
                        candidates[entry_id][(source_id, japanese)].add(chinese)
    counters['uncertainEntriesSkipped'] = len(checked_ids)
    output.parent.mkdir(parents=True, exist_ok=True); manifest.parent.mkdir(parents=True, exist_ok=True)
    samples = []
    with output.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as gz, io.TextIOWrapper(gz, encoding='utf-8', newline='\n') as sql:
        sql.write('\\set ON_ERROR_STOP on\nBEGIN;\nCREATE TEMP TABLE jitendex_example_seed(entry_id uuid,revision_id uuid,allowed_revisions uuid[],patch text) ON COMMIT DROP;\nCOPY jitendex_example_seed FROM STDIN WITH(FORMAT csv);\n')
        writer = csv.writer(sql, lineterminator='\n')
        for entry_id, expected in wanted.items():
            if entry_id in checked_ids:
                continue
            patch = match_translations(expected, candidates[entry_id], counters)
            if not patch:
                continue
            encoded = json.dumps(patch, ensure_ascii=False, separators=(',', ':'))
            revision = uuid.uuid5(uuid.UUID(entry_id), 'jitendex-examples:' + VERSION + ':' + hashlib.sha256(encoded.encode()).hexdigest())
            writer.writerow((entry_id, str(revision), '{' + ','.join(allowed[entry_id]) + '}', encoded))
            counters['preparedEntries'] += 1
            if written[entry_id] in {'苛める', '猫', '学校', '食べる'}:
                samples.append({'entryId': entry_id, 'written': written[entry_id], 'translations': patch})
        sql.write('\\.\n'); sql.write(sql_footer(counters['preparedEntries']))
    result = {'source': 'JITENDEX_ZH_EXAMPLES', 'sourceVersion': VERSION, 'sourceUrl': SOURCE_URL,
              'sourceSha256': SOURCE_SHA256, 'uncertainSha256': UNCERTAIN_SHA256, 'uncertainIds': len(excluded),
              'license': LICENSE, 'originalExampleLicense': 'CC BY 2.0 FR', 'projectUrl': PROJECT_URL,
              'baselineSqlSha256': sha256(baseline_sql), 'examplesSqlSha256': sha256(examples_sql),
              'tomoshiSqlSha256': sha256(tomoshi_sql), 'counts': dict(counters), 'samples': samples,
              'selection': 'same JMdict ID + primary written form + allowed reading + Tatoeba ID + exact Japanese text; conflicting translations and uncertain IDs skipped; existing Chinese preserved',
              'sqlFile': output.name, 'sqlGzipSha256': sha256(output)}
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result['counts'], ensure_ascii=False))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ['source', 'uncertain', 'baseline_sql', 'examples_sql', 'tomoshi_sql', 'output']:
        parser.add_argument(argument, type=Path)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    generate(args.source, args.uncertain, args.baseline_sql, args.examples_sql, args.tomoshi_sql, args.output, args.manifest)
