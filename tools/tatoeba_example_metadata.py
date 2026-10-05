#!/usr/bin/env python3
"""为JMdict精选例句提取Tatoeba作者、已核对的日英对应和直接中文译文。"""
from __future__ import annotations
import argparse
import bz2
import hashlib
import io
import json
import tarfile
from pathlib import Path
from jmdict_examples import entries, examples


def lines(path: Path):
    """官方导出分为bz2文本和tar.bz2，均流式读取，不解压到工作目录。"""
    if path.name.endswith('.tar.bz2'):
        with tarfile.open(path, 'r|bz2') as archive:
            for member in archive:
                if member.isfile():
                    with archive.extractfile(member) as stream:
                        for line in stream:
                            yield line.decode('utf-8')
    else:
        with bz2.open(path, 'rt', encoding='utf-8') as stream:
            yield from stream


def metadata(examples_file: Path, source: Path, output: Path):
    """只保留实际使用的句子数据；词义匹配仍由JMdict负责，不用译文词面猜测。"""
    wanted = {}
    for entry in entries(examples_file):
        sequence = entry.findtext('ent_seq')
        for sense in entry.findall('sense'):
            for example in examples(sense, sequence):
                identifier = example['attribution']['sourceId']
                wanted.setdefault(identifier, {'text': example['text'], 'englishTexts': set()})['englishTexts'].add(example['translation'])
    japanese = {}
    for line in lines(source / 'jpn_sentences_detailed.tsv.bz2'):
        row = line.rstrip('\n').split('\t')
        if len(row) >= 6 and row[0] in wanted and row[2] == wanted[row[0]]['text']:
            japanese[row[0]] = {'text': row[2], 'author': '' if row[3] == '\\N' else row[3], 'english': [], 'translations': {}}
    indices = {}
    for line in lines(source / 'jpn_indices.tar.bz2'):
        row = line.rstrip('\n').split('\t')
        if len(row) >= 2 and row[0] in japanese:
            indices.setdefault(row[1], []).append(row[0])
    for line in lines(source / 'eng_sentences_detailed.tsv.bz2'):
        row = line.rstrip('\n').split('\t')
        if len(row) >= 6 and row[0] in indices:
            for identifier in indices[row[0]]:
                if row[2] in wanted[identifier]['englishTexts']:
                    japanese[identifier]['english'].append({'id': row[0], 'text': row[2], 'author': '' if row[3] == '\\N' else row[3]})
    chinese = {}
    for line in lines(source / 'cmn_sentences_detailed.tsv.bz2'):
        row = line.rstrip('\n').split('\t')
        if len(row) >= 6 and 2 <= len(row[2]) <= 150:
            chinese[row[0]] = {'text': row[2], 'author': '' if row[3] == '\\N' else row[3]}
    # 只用日语到中文的直接翻译，不沿英语中转链冒充逐句对应。
    for line in lines(source / 'links.tar.bz2'):
        row = line.rstrip('\n').split('\t')
        if len(row) >= 2 and row[0] in japanese and row[1] in chinese:
            identifier = row[0]; candidate = {'id': row[1], **chinese[row[1]]}
            previous = japanese[identifier]['translations'].get('zh')
            if previous is None or (len(candidate['text']), int(candidate['id'])) < (len(previous['text']), int(previous['id'])):
                japanese[identifier]['translations']['zh'] = candidate
    valid = {identifier: value for identifier, value in japanese.items() if value['english']}
    result = {'source': 'https://tatoeba.org/en/downloads', 'license': 'CC BY 2.0 FR',
        'files': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in source.iterdir() if path.is_file()},
        'sentences': valid}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')
    print(json.dumps({'checkedJapaneseEnglishPairs': len(valid), 'directChineseTranslations': sum(bool(value['translations']) for value in valid.values()), 'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('examples', type=Path); parser.add_argument('source', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args(); metadata(args.examples, args.source, args.output)
