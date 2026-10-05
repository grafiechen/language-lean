#!/usr/bin/env python3
"""把 EDRDG 官方 JMdict_b XML 转换成可重复执行的 PostgreSQL 初始化 SQL。"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import re
import shutil
import tempfile
import unicodedata
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

NAMESPACE = uuid.UUID("0e5021e8-8a52-47d8-96c1-0f23c7f38490")
SYSTEM_ACTOR = uuid.UUID("00000000-0000-0000-0000-000000000001")
SOURCE_CODE = "JMDICT"
SOURCE_URL = "https://www.edrdg.org/pub/Nihongo/JMdict_b.gz"
PROJECT_URL = "https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html"
LICENSE_URL = "https://www.edrdg.org/edrdg/licence.html"
LICENSE_NAME = "CC BY-SA 4.0"
XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="官方 JMdict_b.gz 文件")
    parser.add_argument("output", type=Path, help="输出的 .sql.gz 文件")
    parser.add_argument("--manifest", type=Path, required=True, help="输出生成清单 JSON")
    return parser.parse_args()


def source_version(source: Path) -> str:
    """从 XML 头部读取官方生成日期，不用本机下载时间冒充数据版本。"""
    with gzip.open(source, "rt", encoding="utf-8") as stream:
        head = stream.read(256_000)
    match = re.search(r"JMdict created:\s*(\d{4}-\d{2}-\d{2})", head)
    if not match:
        raise ValueError("JMdict 文件头中没有生成日期")
    return match.group(1)


def clean(value: str | None, limit: int) -> str:
    """统一清理外部文本，并满足现有内容契约的字段长度。"""
    result = " ".join((value or "").split())
    return result[:limit]


def stable_id(kind: str, sequence: str, index: int = 0, value: str = "") -> uuid.UUID:
    """相同 JMdict 版本反复生成时沿用稳定 ID，便于幂等部署和以后更新。"""
    return uuid.uuid5(NAMESPACE, f"{kind}:{sequence}:{index}:{value}")


def selected_readings(entry: ET.Element, written: str, has_kanji: bool) -> list[str]:
    readings: list[str] = []
    for reading in entry.findall("r_ele"):
        value = clean(reading.findtext("reb"), 200)
        restrictions = {clean(node.text, 200) for node in reading.findall("re_restr")}
        if value and (not has_kanji or not restrictions or written in restrictions) and value not in readings:
            readings.append(value)
        if len(readings) == 20:
            break
    return readings


def selected_senses(entry: ET.Element, sequence: str, written: str, readings: list[str]) -> list[dict]:
    senses: list[dict] = []
    inherited_pos: list[str] = []
    for source_index, sense in enumerate(entry.findall("sense")):
        kanji_restrictions = {clean(node.text, 200) for node in sense.findall("stagk")}
        reading_restrictions = {clean(node.text, 200) for node in sense.findall("stagr")}
        if kanji_restrictions and written not in kanji_restrictions:
            continue
        if reading_restrictions and not reading_restrictions.intersection(readings):
            continue
        current_pos = [clean(node.text, 100) for node in sense.findall("pos") if clean(node.text, 100)]
        if current_pos:
            inherited_pos = current_pos
        glosses = []
        for node in sense.findall("gloss"):
            language = node.attrib.get(XML_LANG, "eng")
            text = clean(node.text, 4000)
            if language in {"eng", "en"} and text:
                glosses.append(text)
        gloss = clean("; ".join(dict.fromkeys(glosses)), 4000)
        if not gloss:
            continue
        senses.append({
            "id": str(stable_id("sense", sequence, source_index)),
            "partOfSpeech": clean("; ".join(dict.fromkeys(inherited_pos)), 100),
            "gloss": gloss,
            "examples": [],
        })
        if len(senses) == 50:
            break
    return senses


def convert(entry: ET.Element, version: str, seen: set[str]) -> tuple[str, str, str, str, str] | None:
    """选择 JMdict 的主要书写形式，并按限制字段筛选其读音和词义。"""
    sequence = clean(entry.findtext("ent_seq"), 32)
    kanji = [clean(node.text, 200) for node in entry.findall("k_ele/keb") if clean(node.text, 200)]
    fallback_readings = [clean(node.text, 200) for node in entry.findall("r_ele/reb") if clean(node.text, 200)]
    written = kanji[0] if kanji else (fallback_readings[0] if fallback_readings else "")
    normalized = unicodedata.normalize("NFKC", written).strip()
    if not sequence or not written or len(written) > 200 or not normalized or normalized in seen:
        return None
    readings = selected_readings(entry, written, bool(kanji))
    senses = selected_senses(entry, sequence, written, readings)
    if not readings or not senses:
        return None
    seen.add(normalized)
    entry_id = stable_id("entry", sequence)
    content = {
        "schemaVersion": 1,
        "readings": [
            {"id": str(stable_id("reading", sequence, index, value)), "reading": value, "pronunciationText": value}
            for index, value in enumerate(readings)
        ],
        "senses": senses,
        "sourceName": f"JMdict (EDRDG) {version}",
        "license": LICENSE_NAME,
    }
    return (str(entry_id), written, normalized, str(stable_id("revision", sequence)),
            json.dumps(content, ensure_ascii=False, separators=(",", ":")))


def write_sql(source: Path, output: Path, manifest_path: Path) -> dict:
    """先流式解析到临时 CSV，再组装压缩 SQL，避免把整本词典放进内存。"""
    version = source_version(source)
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    seen: set[str] = set()
    total = imported = skipped = 0
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        entry_path = Path(temporary) / "entries.csv"
        revision_path = Path(temporary) / "revisions.csv"
        with entry_path.open("w", encoding="utf-8", newline="") as entry_file, revision_path.open("w", encoding="utf-8", newline="") as revision_file:
            entry_writer = csv.writer(entry_file, lineterminator="\n")
            revision_writer = csv.writer(revision_file, lineterminator="\n")
            with gzip.open(source, "rb") as xml:
                for _, element in ET.iterparse(xml, events=("end",)):
                    if element.tag != "entry":
                        continue
                    total += 1
                    converted = convert(element, version, seen)
                    if converted is None:
                        skipped += 1
                    else:
                        entry_id, written, normalized, revision_id, content = converted
                        entry_writer.writerow((entry_id, written, normalized))
                        revision_writer.writerow((revision_id, entry_id, content))
                        imported += 1
                    element.clear()

        release_id = stable_id("release", version, value=source_hash)
        option_id = stable_id("source-option", SOURCE_CODE)
        timestamp = version + "T00:00:00Z"
        with output.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as zipped, io.TextIOWrapper(zipped, encoding="utf-8", newline="\n") as sql:
            sql.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
            sql.write("CREATE TEMP TABLE jmdict_entry_seed (id uuid, written varchar(200), normalized_written_key varchar(200)) ON COMMIT DROP;\n")
            sql.write("COPY jmdict_entry_seed (id, written, normalized_written_key) FROM STDIN WITH (FORMAT csv);\n")
            with entry_path.open("r", encoding="utf-8", newline="") as rows:
                shutil.copyfileobj(rows, sql)
            sql.write("\\.\n")
            sql.write("CREATE TEMP TABLE jmdict_revision_seed (id uuid, entry_id uuid, content text) ON COMMIT DROP;\n")
            sql.write("COPY jmdict_revision_seed (id, entry_id, content) FROM STDIN WITH (FORMAT csv);\n")
            with revision_path.open("r", encoding="utf-8", newline="") as rows:
                shutil.copyfileobj(rows, sql)
            sql.write("\\.\n")
            sql.write(f"""
INSERT INTO system_dictionary_item(id, dictionary_code, item_value, display_name, description, sort_order, enabled, version)
VALUES ('{option_id}', 'CONTENT_SOURCE', 'JMdict', 'JMdict', 'EDRDG 维护的日英开源词典。', 20, true, 0)
ON CONFLICT (dictionary_code, item_value) DO NOTHING;

INSERT INTO dictionary_entry(id, language_code, script_code, written, normalized_written_key, status,
                             current_revision, draft_content, draft_base_revision, created_by, updated_by,
                             created_at, updated_at, version, origin_type)
SELECT id, 'ja', 'Jpan', written, normalized_written_key, 'PUBLISHED', 1, NULL, NULL,
       '{SYSTEM_ACTOR}', '{SYSTEM_ACTOR}', '{timestamp}', '{timestamp}', 0, 'OPEN_SOURCE'
FROM jmdict_entry_seed
ON CONFLICT DO NOTHING;

INSERT INTO dictionary_revision(id, entry_id, revision_number, content, published_by, published_at, note)
SELECT revision.id, revision.entry_id, 1, revision.content, '{SYSTEM_ACTOR}', '{timestamp}',
       'JMdict_b {version} 初始导入'
FROM jmdict_revision_seed revision
JOIN dictionary_entry entry ON entry.id = revision.entry_id
ON CONFLICT DO NOTHING;

-- 检索字段取实际当前公开版，重跑种子时不覆盖人工修订或原始词典正文。
UPDATE dictionary_entry entry
SET published_readings_search = dictionary_readings_search(revision.content)
FROM dictionary_revision revision, jmdict_entry_seed seed
WHERE entry.id = seed.id AND revision.entry_id = entry.id
  AND revision.revision_number = entry.current_revision;

INSERT INTO dictionary_source_release(id, source_code, source_version, source_url, source_sha256,
                                      license_name, entry_count, imported_at)
VALUES ('{release_id}', '{SOURCE_CODE}', '{version}', '{SOURCE_URL}', '{source_hash}',
        '{LICENSE_NAME}', {imported}, CURRENT_TIMESTAMP)
ON CONFLICT (source_code, source_version, source_sha256) DO NOTHING;
COMMIT;
""")

    sql_hash = hashlib.sha256(output.read_bytes()).hexdigest()
    manifest = {
        "source": SOURCE_CODE,
        "sourceVersion": version,
        "sourceUrl": SOURCE_URL,
        "projectUrl": PROJECT_URL,
        "license": LICENSE_NAME,
        "licenseUrl": LICENSE_URL,
        "sourceSha256": source_hash,
        "sourceEntries": total,
        "generatedEntries": imported,
        "skippedEntries": skipped,
        "sqlFile": output.name,
        "sqlGzipSha256": sql_hash,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    args = arguments()
    manifest = write_sql(args.source, args.output, args.manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
