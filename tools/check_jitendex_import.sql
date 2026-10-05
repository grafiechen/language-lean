\set ON_ERROR_STOP on
-- 只读验收：将本次例句版本与其直接前驱比较，原历史不更新。
CREATE TEMP TABLE jitendex_changed AS
SELECT current.entry_id AS id,current.content,previous.content AS before_content
FROM dictionary_revision current JOIN dictionary_revision previous
ON previous.entry_id=current.entry_id AND previous.revision_number=current.revision_number-1
WHERE current.note LIKE 'Jitendex 中文版 2026-09-27%';
DO $$ BEGIN
  IF EXISTS (
    SELECT 1 FROM jitendex_changed c
    WHERE c.content::jsonb-'senses' <> c.before_content::jsonb-'senses'
    OR (SELECT jsonb_agg(jsonb_set(s.value,'{examples}',(
       SELECT coalesce(jsonb_agg(x.value-'translations' ORDER BY x.n),'[]'::jsonb)
       FROM jsonb_array_elements(s.value->'examples') WITH ORDINALITY x(value,n))) ORDER BY s.n)
       FROM jsonb_array_elements(c.content::jsonb->'senses') WITH ORDINALITY s(value,n))
    <> (SELECT jsonb_agg(jsonb_set(s.value,'{examples}',(
       SELECT coalesce(jsonb_agg(x.value-'translations' ORDER BY x.n),'[]'::jsonb)
       FROM jsonb_array_elements(s.value->'examples') WITH ORDINALITY x(value,n))) ORDER BY s.n)
       FROM jsonb_array_elements(c.before_content::jsonb->'senses') WITH ORDINALITY s(value,n))
  ) THEN RAISE EXCEPTION '原词义、读音、英文、例句原文或顺序发生变化'; END IF;
  IF EXISTS (
    SELECT 1 FROM jitendex_changed c
    CROSS JOIN LATERAL jsonb_array_elements(c.before_content::jsonb->'senses') s(value)
    CROSS JOIN LATERAL jsonb_array_elements(s.value->'examples') x(value)
    CROSS JOIN LATERAL jsonb_each(coalesce(x.value->'translations','{}'::jsonb)) t(key,value)
    WHERE NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(c.content::jsonb->'senses') ns(value)
      CROSS JOIN LATERAL jsonb_array_elements(ns.value->'examples') nx(value)
      WHERE nx.value->>'id'=x.value->>'id' AND nx.value->'translations'->t.key=t.value)
  ) THEN RAISE EXCEPTION '已有译文或译文来源被覆盖'; END IF;
  IF EXISTS(SELECT entry_id FROM dictionary_revision GROUP BY entry_id
            HAVING min(revision_number)<>1 OR max(revision_number)<>count(*))
    THEN RAISE EXCEPTION '历史版本不连续'; END IF;
END $$;
SELECT 'jitendex_revisions',count(*) FROM jitendex_changed;
SELECT 'dictionary_entries',count(*) FROM dictionary_entry;
SELECT 'current_examples',count(*) AS total,
count(*) FILTER(WHERE x.value->'translations' ? 'zh-Hans') AS simplified,
count(*) FILTER(WHERE x.value->'translations' ? 'zh') AS generic_chinese
FROM dictionary_entry e JOIN dictionary_revision r ON r.entry_id=e.id AND r.revision_number=e.current_revision
CROSS JOIN LATERAL jsonb_array_elements(r.content::jsonb->'senses') s(value)
CROSS JOIN LATERAL jsonb_array_elements(s.value->'examples') x(value);
SELECT source_code,source_version,entry_count FROM dictionary_source_release WHERE source_code='JITENDEX_ZH_EXAMPLES';
