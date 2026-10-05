#!/usr/bin/env python3
"""审计初始词典，区分明确损坏与合法罕用数据；不按词频或陌生程度删除词条。"""
import argparse
import collections
import json
import unicodedata
from pathlib import Path
from jmdict_examples import entries
from jmdict_to_postgres import convert, source_version


def audit(source: Path, output: Path):
    """验证实际生成的正文，并单独报告源词典中因全局写法唯一键跳过的身份。"""
    counts = collections.Counter(); damaged = []; seen = set(); version = source_version(source)
    for element in entries(source):
        counts['sourceEntries'] += 1
        value = convert(element, version, seen)
        if value is None:
            counts['skippedByExistingImportRules'] += 1
            continue
        counts['generatedEntries'] += 1
        content = json.loads(value[4])
        fields = [value[1], *(r['reading'] for r in content['readings']), *(s['gloss'] for s in content['senses'])]
        reason = []
        if any('\ufffd' in text or any(unicodedata.category(char) in {'Cc', 'Cs'} for char in text) for text in fields):
            reason.append('乱码或控制字符')
        if not content['readings'] or not content['senses'] or any(not text.strip() for text in fields): reason.append('空写法、读音或释义')
        if reason: damaged.append({'id': value[0], 'written': value[1], 'reason': reason})
    result = {'sourceVersion': version, 'counts': dict(counts), 'damagedEntries': damaged, 'damagedEntryCount': len(damaged),
        'policy': '只将乱码、非法控制字符和空必填正文列为明确损坏；罕用词、古语、专名、数字词及外来语不自动删除。源同写法跳过不代表词义错误。'}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8'); print(json.dumps(result,ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('source',type=Path); parser.add_argument('output',type=Path)
    args=parser.parse_args(); audit(args.source,args.output)
