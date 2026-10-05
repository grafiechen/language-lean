#!/usr/bin/env python3
"""以官方词义关联补充例句，生成可幂等部署的线性版本SQL；不改动学习数据。"""
from __future__ import annotations
import argparse
import copy
import csv
import gzip
import hashlib
import io
import json
import unicodedata
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from jmdict_to_postgres import convert, clean, selected_readings, selected_senses, stable_id, source_version, XML_LANG, SYSTEM_ACTOR


def entries(path: Path):
    """流式解析官方XML，只保留一个词条的树，避免整本XML占据内存。"""
    with gzip.open(path, 'rb') as stream:
        for _, element in ET.iterparse(stream, events=('end',)):
            if element.tag == 'entry':
                yield element
                element.clear()


def examples(sense: ET.Element, sequence: str) -> list[dict]:
    """采用官方按词义选定的例句，拒绝坏字符、超长文本及缺失出处；不做字符串猜配。"""
    result = []
    for element in sense.findall('example'):
        source = element.find('ex_srce')
        if source is None or source.attrib.get('exsrc_type') != 'tat' or not (source.text or '').isdigit():
            continue
        sentences = {node.attrib.get(XML_LANG, 'eng'): clean(node.text, 2001) for node in element.findall('ex_sent')}
        japanese, english = sentences.get('jpn', ''), sentences.get('eng', '')
        # 只保留长度适合朗读的一两句；乱码和控制字符不进入学习资料。
        if not 4 <= len(japanese) <= 100 or not english or len(english) > 400:
            continue
        if any('\ufffd' in text or any(unicodedata.category(c) in {'Cc', 'Cs'} for c in text) for text in (japanese, english)):
            continue
        if japanese in {item['text'] for item in result}:
            continue
        identifier = source.text
        result.append({'id': str(stable_id('tatoeba-example', sequence, value=identifier + ':' + japanese)),
            'text': japanese, 'pronunciationText': japanese, 'translation': english,
            'translationLanguage': 'en', 'translations': {}, 'attribution': {
                'sourceName': 'Tatoeba / Tanaka Corpus · JMdict精选', 'license': 'CC BY 2.0 FR',
                'sourceUrl': 'https://tatoeba.org/en/sentences/show/' + identifier, 'sourceId': identifier, 'author': ''}})
    return sorted(result, key=lambda item: (len(item['text']), item['text']))[:3]


def generate(base: Path, extended: Path, output: Path, manifest: Path, metadata_path: Path):
    """按稳定词条ID和原释义匹配。来源词义变化或人工修改的版本不会被强行覆盖。"""
    version = source_version(base)
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))['sentences']
    baseline = {}; seen = set()
    for element in entries(base):
        value = convert(element, version, seen)
        if value:
            baseline[element.findtext('ent_seq')] = value
    rows = []; count = 0; mismatched = 0
    for element in entries(extended):
        sequence = element.findtext('ent_seq')
        value = baseline.get(sequence)
        if not value or not element.findall('sense/example'):
            continue
        entry_id, written, _, _, original_json = value
        original = json.loads(original_json); enriched = copy.deepcopy(original)
        readings = selected_readings(element, written, bool(element.findall('k_ele')))
        selected = {sense['id']: sense for sense in selected_senses(element, sequence, written, readings)}
        by_id = {str(stable_id('sense', sequence, index)): sense for index, sense in enumerate(element.findall('sense'))}
        added = 0
        for sense in enriched['senses']:
            incoming = selected.get(sense['id'])
            if incoming is None or incoming['gloss'] != sense['gloss'] or incoming['partOfSpeech'] != sense['partOfSpeech']:
                mismatched += 1
                continue
            sense['glossLanguage'] = 'en'; sense['translations'] = {}
            sense['examples'] = examples(by_id[sense['id']], sequence)
            checked = []
            for example in sense['examples']:
                provenance = metadata.get(example['attribution']['sourceId'])
                # 源句已删除、改动或日英关联不能核实时跳过，避免带入陈旧或错配的例句。
                english = next((value for value in provenance['english'] if value['text'] == example['translation']), None) if provenance else None
                if not provenance or provenance['text'] != example['text'] or not english:
                    continue
                authors = []
                if provenance['author']: authors.append('日语：' + provenance['author'])
                if english['author']: authors.append('英语：' + english['author'])
                example['attribution']['author'] = '；'.join(authors)
                for language, translation in provenance['translations'].items():
                    example['translations'][language] = {'text': translation['text'], 'sourceName': 'Tatoeba', 'license': 'CC BY 2.0 FR',
                        'sourceUrl': 'https://tatoeba.org/en/sentences/show/' + translation['id'], 'author': translation['author']}
                checked.append(example)
            sense['examples'] = checked
            added += len(sense['examples'])
        if not added:
            continue
        enriched_json = json.dumps(enriched, ensure_ascii=False, separators=(',', ':'))
        revision_id = uuid.uuid5(uuid.UUID(entry_id), 'examples:' + hashlib.sha256(enriched_json.encode()).hexdigest())
        rows.append((entry_id, str(revision_id), original_json, enriched_json)); count += added
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as zipped, io.TextIOWrapper(zipped, encoding='utf-8', newline='\n') as sql:
        sql.write('\\set ON_ERROR_STOP on\nBEGIN;\n')
        sql.write('CREATE TEMP TABLE example_seed(entry_id uuid, revision_id uuid, base_content text, content text) ON COMMIT DROP;\nCOPY example_seed FROM STDIN WITH(FORMAT csv);\n')
        writer = csv.writer(sql, lineterminator='\n'); writer.writerows(rows); sql.write('\\.\n')
        # 表锁防止发布或恢复并发插入相同版本；只追加，旧版不可改写。
        sql.write(f"""
LOCK TABLE dictionary_entry IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP TABLE eligible_examples ON COMMIT DROP AS
SELECT seed.*, entry.current_revision + 1 AS next_revision
FROM example_seed seed JOIN dictionary_entry entry ON entry.id = seed.entry_id
JOIN dictionary_revision current ON current.entry_id = entry.id AND current.revision_number = entry.current_revision
WHERE entry.status = 'PUBLISHED' AND entry.origin_type = 'OPEN_SOURCE' AND entry.draft_content IS NULL
  AND current.content::jsonb = seed.base_content::jsonb
  AND NOT EXISTS (SELECT 1 FROM dictionary_revision old WHERE old.id = seed.revision_id);
INSERT INTO dictionary_revision(id, entry_id, revision_number, content, published_by, published_at, note)
SELECT revision_id, entry_id, next_revision, content, '{SYSTEM_ACTOR}', now(), '补充JMdict精选词义例句；独立标注Tatoeba来源与许可'
FROM eligible_examples;
UPDATE dictionary_entry entry SET current_revision = seed.next_revision,
published_readings_search = dictionary_readings_search(seed.content), updated_at = now(), updated_by = '{SYSTEM_ACTOR}', version = version + 1
FROM eligible_examples seed WHERE entry.id = seed.entry_id;
SELECT (SELECT count(*) FROM example_seed) AS prepared_entries, count(*) AS applied_entries FROM eligible_examples;
COMMIT;
""")
    result = {'baseVersion': version, 'exampleVersion': source_version(extended), 'entryCount': len(rows), 'exampleCount': count,
        'mismatchedSensesSkipped': mismatched, 'baseSha256': hashlib.sha256(base.read_bytes()).hexdigest(),
        'exampleSourceSha256': hashlib.sha256(extended.read_bytes()).hexdigest(), 'sqlGzipSha256': hashlib.sha256(output.read_bytes()).hexdigest(),
        'metadataSha256': hashlib.sha256(metadata_path.read_bytes()).hexdigest(),
        'chineseExampleTranslations': sum(len(example['translations']) for row in rows for sense in json.loads(row[3])['senses'] for example in sense['examples']),
        'sourceUrl': 'https://www.edrdg.org/pub/Nihongo/JMdict_e_examp.gz', 'exampleLicense': 'CC BY 2.0 FR',
        'selection': 'official sense-linked examples, 4..100 Japanese characters, <=400 English characters, <=3 per sense, shortest first'}
    manifest.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8'); print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base', type=Path); parser.add_argument('examples', type=Path)
    parser.add_argument('output', type=Path); parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--metadata', required=True, type=Path)
    args = parser.parse_args(); generate(args.base, args.examples, args.output, args.manifest, args.metadata)
