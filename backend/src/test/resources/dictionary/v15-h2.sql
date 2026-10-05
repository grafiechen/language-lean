-- H2验证应用的事务及搜索边界；PostgreSQL函数、回填和GIN另用真实数据库验收。
ALTER TABLE dictionary_entry ADD COLUMN published_readings_search text NOT NULL DEFAULT '';
ALTER TABLE dictionary_entry ADD COLUMN draft_readings_search text NOT NULL DEFAULT '';
