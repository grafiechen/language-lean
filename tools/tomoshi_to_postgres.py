#!/usr/bin/env python3
"""将 Tomoshi 的简繁中文释义合并到已有 JMdict 身份，生成只追加版本的部署 SQL。"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import sqlite3
import unicodedata
import uuid
from collections import Counter
from pathlib import Path

from jmdict_examples import entries
from jmdict_to_postgres import SYSTEM_ACTOR, clean, convert, source_version, stable_id

VERSION = '2026-09-02'
PROJECT_URL = 'https://github.com/tomoshi-app/tomoshi-dict-data'
SOURCE_URL = PROJECT_URL + f'/releases/download/v{VERSION}/tomoshi-dict-open.db.zst'
COMPRESSED_SHA256 = '7153dfd7a8e42e2d920308370eac90cf9f2e4b4cfe67fb9a86e9aa1c89494073'
DATABASE_SHA256 = '8b19c7d65a7d7d6df9afc58832b17b22fd349724e5d06d2acf3bb9a6c4b0ed9d'
LICENSE = 'CC BY-SA 4.0'
SOURCE_NAME = f'Tomoshi (Y1Z) {VERSION} · 机器翻译（模型校订）'


def sha256(path: Path) -> str:
    """分块计算校验值，不把整份数据库读入内存。"""
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def chinese_text(value: dict | None) -> str | None:
    """只读取释义，不导入没有日文原句及独立出处的孤立例句译文。"""
    if not isinstance(value, dict) or not isinstance(value.get('glosses'), list):
        return None
    glosses = []
    for item in value['glosses']:
        if not isinstance(item, dict) or not isinstance(item.get('text'), str):
            return None
        text = item['text'].strip()
        if not text or '\ufffd' in text or any(unicodedata.category(c) in {'Cc', 'Cs'} and c not in '\n\t' for c in text):
            return None
        glosses.append(text)
    text = '；'.join(dict.fromkeys(glosses))
    # 不截断超长内容，不把未译的英语当成中文导入。
    if not text or len(text) > 4000 or not any('\u3400' <= c <= '\u9fff' for c in text):
        return None
    return text


def sense_patches(sequence: str, baseline: dict, incoming: dict, simplified: dict,
                  traditional: dict, counters: Counter) -> dict:
    """稳定 ID 对应原义项下标，还需英文释义和词性一致；版本错位不会靠词头猜配。"""
    incoming_senses = incoming.get('senses', [])
    if not isinstance(incoming_senses, list) or str(incoming.get('id')) != sequence:
        counters['invalidSourceEntry'] += 1
        return {}
    source_by_id = {str(stable_id('sense', sequence, index)): (index, sense)
                    for index, sense in enumerate(incoming_senses)}
    result = {}
    inherited_pos = []
    source_pos = {}
    for index, sense in enumerate(incoming_senses):
        if sense.get('pos'):
            inherited_pos = sense['pos']
        source_pos[index] = clean('; '.join(dict.fromkeys(inherited_pos)), 100)
    for sense in baseline['senses']:
        item = source_by_id.get(sense['id'])
        if item is None:
            counters['missingSourceSense'] += 1
            continue
        index, source = item
        glosses = [clean(value.get('text'), 4000) for value in source.get('glosses', [])
                   if value.get('lang') in {'eng', 'en'} and clean(value.get('text'), 4000)]
        english = clean('; '.join(dict.fromkeys(glosses)), 4000)
        if english != sense['gloss'] or source_pos[index] != sense['partOfSpeech']:
            counters['changedSourceSense'] += 1
            continue
        translations = {}
        for language, data in [('zh-Hans', simplified), ('zh-Hant', traditional)]:
            text = chinese_text(data.get('senses', {}).get(str(index)))
            if text is None:
                counters['invalidOrMissingTranslation'] += 1
                continue
            translations[language] = {'text': text, 'sourceName': SOURCE_NAME, 'license': LICENSE,
                                      'sourceUrl': PROJECT_URL + f'/releases/tag/v{VERSION}', 'author': 'Y1Z'}
            counters[language] += 1
        if translations:
            result[sense['id']] = translations
            counters['translatedSenses'] += 1
    return result


def example_revisions(path: Path) -> dict[str, str]:
    """读取已有例句种子中的稳定版本 ID；只允许补充这些已知导入快照。"""
    with gzip.open(path, 'rt', encoding='utf-8', newline='') as stream:
        for line in stream:
            if line.startswith('COPY example_seed FROM STDIN'):
                break
        else:
            raise ValueError('例句 SQL 缺少预期 COPY 段')
        result = {}
        for row in csv.reader(stream):
            if row == ['\\.']:
                return result
            if len(row) != 4:
                raise ValueError('例句 SQL 行格式不正确')
            entry_id, revision_id = row[:2]
            uuid.UUID(entry_id); uuid.UUID(revision_id)
            # 例句版正文也参与校验，拒绝拿错文件或非生成版本。
            expected = uuid.uuid5(uuid.UUID(entry_id), 'examples:' + hashlib.sha256(row[3].encode()).hexdigest())
            if str(expected) != revision_id:
                raise ValueError('例句 SQL 的稳定版本校验失败')
            result[entry_id] = revision_id
        raise ValueError('例句 SQL 缺少 COPY 结束标记')


def sql_footer(entry_count: int) -> str:
    """只更新已知导入版本；保留草稿、封禁、人工发布、所有例句与学习数据。"""
    release_id = stable_id('release', VERSION, value='TOMOSHI:' + COMPRESSED_SHA256)
    option_id = stable_id('source-option', 'TOMOSHI')
    return f"""
-- 阻止并发发布及恢复穿插；历史正文只追加，不 UPDATE。
LOCK TABLE dictionary_entry IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE eligible_tomoshi ON COMMIT DROP AS
SELECT seed.entry_id, seed.revision_id, entry.current_revision + 1 AS next_revision,
       jsonb_set(current.content::jsonb, '{{senses}}', (
         SELECT jsonb_agg(CASE WHEN seed.patch::jsonb ? (sense.value->>'id') THEN
           jsonb_set(jsonb_set(sense.value, '{{translations}}',
             (seed.patch::jsonb->(sense.value->>'id')) ||
             coalesce(sense.value->'translations', '{{}}'::jsonb)), '{{glossLanguage}}', '"en"'::jsonb)
           ELSE sense.value END ORDER BY sense.ordinality)
         FROM jsonb_array_elements(current.content::jsonb->'senses') WITH ORDINALITY AS sense(value, ordinality)
       ))::text AS content
FROM tomoshi_seed seed JOIN dictionary_entry entry ON entry.id = seed.entry_id
JOIN dictionary_revision current ON current.entry_id = entry.id AND current.revision_number = entry.current_revision
WHERE entry.language_code = 'ja' AND entry.origin_type = 'OPEN_SOURCE' AND entry.status = 'PUBLISHED'
  AND entry.draft_content IS NULL AND current.id = ANY(seed.allowed_revisions)
  AND NOT EXISTS (SELECT 1 FROM dictionary_revision old WHERE old.id = seed.revision_id);

INSERT INTO dictionary_revision(id, entry_id, revision_number, content, published_by, published_at, note)
SELECT revision_id, entry_id, next_revision, content, '{SYSTEM_ACTOR}', now(),
       'Tomoshi {VERSION} 简繁中文释义；机器翻译经模型校订，可继续人工修订；CC BY-SA 4.0'
FROM eligible_tomoshi;
UPDATE dictionary_entry entry SET current_revision = seed.next_revision,
published_readings_search = dictionary_readings_search(seed.content), updated_at = now(),
updated_by = '{SYSTEM_ACTOR}', version = version + 1
FROM eligible_tomoshi seed WHERE entry.id = seed.entry_id;

INSERT INTO system_dictionary_item(id, dictionary_code, item_value, display_name, description, sort_order, enabled, version)
VALUES ('{option_id}', 'CONTENT_SOURCE', 'Tomoshi', 'Tomoshi 开放词典',
        'JMdict 衍生简繁中文释义；机器翻译经模型校订；Y1Z；CC BY-SA 4.0。', 30, true, 0)
ON CONFLICT (dictionary_code, item_value) DO NOTHING;
INSERT INTO dictionary_source_release(id, source_code, source_version, source_url, source_sha256,
                                      license_name, entry_count, imported_at)
VALUES ('{release_id}', 'TOMOSHI', '{VERSION}', '{SOURCE_URL}', '{COMPRESSED_SHA256}',
        '{LICENSE}', {entry_count}, now())
ON CONFLICT (source_code, source_version, source_sha256) DO NOTHING;
SELECT (SELECT count(*) FROM tomoshi_seed) AS prepared_entries, count(*) AS applied_entries FROM eligible_tomoshi;
COMMIT;
"""


def generate(database: Path, compressed: Path, baseline_path: Path, examples_sql: Path,
             output: Path, manifest: Path) -> dict:
    """核对固定发布包及逐表许可，流式生成可供新环境重放的增量 SQL。"""
    if sha256(database) != DATABASE_SHA256 or sha256(compressed) != COMPRESSED_SHA256:
        raise ValueError('Tomoshi 发布文件 SHA-256 不符合固定版本，拒绝生成')
    version = source_version(baseline_path)
    allowed_examples = example_revisions(examples_sql)
    database_uri = database.resolve().as_uri() + '?mode=ro'
    counters = Counter(); seen = set(); samples = []
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database_uri, uri=True) as db:
        licenses = db.execute("SELECT table_name, license, attribution FROM table_licenses WHERE table_name IN ('entries','zh_defs','zh_defs_zhtw')").fetchall()
        if len(licenses) != 3 or any(row[1] != 'CC-BY-SA-4.0' for row in licenses):
            raise ValueError('Tomoshi 使用表的许可发生变化，拒绝生成')
        with output.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as zipped, io.TextIOWrapper(zipped, encoding='utf-8', newline='\n') as sql:
            sql.write('\\set ON_ERROR_STOP on\nBEGIN;\n')
            sql.write('CREATE TEMP TABLE tomoshi_seed(entry_id uuid, revision_id uuid, allowed_revisions uuid[], patch text) ON COMMIT DROP;\n')
            sql.write('COPY tomoshi_seed FROM STDIN WITH(FORMAT csv);\n')
            writer = csv.writer(sql, lineterminator='\n')
            for element in entries(baseline_path):
                value = convert(element, version, seen)
                if value is None:
                    continue
                counters['baselineEntries'] += 1
                sequence = element.findtext('ent_seq')
                source = db.execute('SELECT e.data, s.data, t.data FROM entries e LEFT JOIN zh_defs s ON s.entry_id=e.id AND s.locale=? LEFT JOIN zh_defs_zhtw t ON t.entry_id=e.id AND t.locale=? WHERE e.id=?', ('zh-CN', 'zh-TW', sequence)).fetchone()
                if source is None:
                    counters['missingSourceEntry'] += 1
                    continue
                entry_id, written, _, original_revision, original_json = value
                patch = sense_patches(sequence, json.loads(original_json), json.loads(source[0]),
                                      json.loads(source[1] or '{}'), json.loads(source[2] or '{}'), counters)
                if not patch:
                    counters['entriesWithoutSafeTranslation'] += 1
                    continue
                patch_json = json.dumps(patch, ensure_ascii=False, separators=(',', ':'))
                revision = uuid.uuid5(uuid.UUID(entry_id), 'tomoshi:' + VERSION + ':' + hashlib.sha256(patch_json.encode()).hexdigest())
                allowed = [original_revision]
                if entry_id in allowed_examples:
                    allowed.append(allowed_examples[entry_id])
                writer.writerow((entry_id, str(revision), '{' + ','.join(allowed) + '}', patch_json))
                counters['preparedEntries'] += 1
                if written in {'苛める', '猫', '学校', '食べる', '走る'}:
                    samples.append({'entryId': entry_id, 'written': written, 'translations': patch})
            sql.write('\\.\n'); sql.write(sql_footer(counters['preparedEntries']))
    result = {'source': 'TOMOSHI', 'sourceVersion': VERSION, 'sourceUrl': SOURCE_URL,
              'projectUrl': PROJECT_URL, 'license': LICENSE, 'author': 'Y1Z',
              'translationMethod': 'LLM-assisted, reviewed by another model; not fully human-verified',
              'sourceSha256': COMPRESSED_SHA256, 'databaseSha256': DATABASE_SHA256,
              'baseVersion': version, 'baseSha256': sha256(baseline_path),
              'examplesSqlSha256': sha256(examples_sql), 'tableLicenses': licenses,
              'counts': dict(counters), 'samples': samples, 'sqlFile': output.name,
              'sqlGzipSha256': sha256(output),
              'selection': 'same JMdict ID + original sense index + exact English gloss and POS; known seed revisions only; examples excluded'}
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result['counts'], ensure_ascii=False))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', type=Path); parser.add_argument('compressed', type=Path)
    parser.add_argument('baseline', type=Path); parser.add_argument('examples_sql', type=Path)
    parser.add_argument('output', type=Path); parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    generate(args.database, args.compressed, args.baseline, args.examples_sql, args.output, args.manifest)
