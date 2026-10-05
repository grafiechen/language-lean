-- 公开读音与草稿读音分别索引，避免未审核内容泄露；检索派生字段不改变词条身份及历史版本。
ALTER TABLE dictionary_entry ADD COLUMN published_readings_search text NOT NULL DEFAULT '';
ALTER TABLE dictionary_entry ADD COLUMN draft_readings_search text NOT NULL DEFAULT '';
COMMENT ON COLUMN dictionary_entry.published_readings_search IS '当前公开版全部读音的NFKC检索文本，以换行分隔，支持与写法相同的包含查询';
COMMENT ON COLUMN dictionary_entry.draft_readings_search IS '当前后台草稿的读音检索文本，仅管理员搜索使用，发布后清空';

-- 与应用的读音派生规则一致；初始化SQL复用此函数，不必重新解释词典快照。
CREATE FUNCTION dictionary_readings_search(content_text text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT coalesce(string_agg(value, E'\n' ORDER BY value), '')
    FROM (
        SELECT DISTINCT btrim(normalize(coalesce(reading ->> 'reading', ''), NFKC), E' \t\n\r\f') AS value
        FROM jsonb_array_elements(coalesce(content_text::jsonb -> 'readings', '[]'::jsonb)) AS reading
    ) normalized WHERE value <> ''
$$;
COMMENT ON FUNCTION dictionary_readings_search(text) IS '从版本化内容提取全部读音并规范化；仅生成检索派生数据，不修改原始快照';

UPDATE dictionary_entry entry
SET published_readings_search = dictionary_readings_search(revision.content)
FROM dictionary_revision revision
WHERE revision.entry_id = entry.id AND revision.revision_number = entry.current_revision;
UPDATE dictionary_entry SET draft_readings_search = dictionary_readings_search(draft_content)
WHERE draft_content IS NOT NULL;

-- 前导通配符的包含查询使用三元组GIN索引；短查询仍由PostgreSQL选择合适执行计划。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX dictionary_written_contains ON dictionary_entry USING gin(normalized_written_key gin_trgm_ops);
CREATE INDEX dictionary_published_readings_contains ON dictionary_entry USING gin(published_readings_search gin_trgm_ops);
CREATE INDEX dictionary_draft_readings_contains ON dictionary_entry USING gin(draft_readings_search gin_trgm_ops)
WHERE draft_content IS NOT NULL;
COMMENT ON INDEX dictionary_written_contains IS '基准写法包含搜索的三元组索引';
COMMENT ON INDEX dictionary_published_readings_contains IS '当前公开读音包含搜索的三元组索引';
COMMENT ON INDEX dictionary_draft_readings_contains IS '仅已有草稿的读音索引，供后台搜索使用';
