CREATE TABLE dictionary_source_release (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL,
    source_version varchar(100) NOT NULL,
    source_url varchar(500) NOT NULL,
    source_sha256 varchar(64) NOT NULL,
    license_name varchar(100) NOT NULL,
    entry_count integer NOT NULL CHECK (entry_count >= 0),
    imported_at timestamp with time zone NOT NULL,
    CONSTRAINT uq_dictionary_source_release UNIQUE (source_code, source_version, source_sha256)
);

COMMENT ON TABLE dictionary_source_release IS '已安装的外部词典发布版本；用于追踪许可、校验源文件并安排后续更新';
COMMENT ON COLUMN dictionary_source_release.id IS '来源发布记录的稳定 UUID';
COMMENT ON COLUMN dictionary_source_release.source_code IS '稳定来源代码，例如 JMDICT';
COMMENT ON COLUMN dictionary_source_release.source_version IS '外部词典发布版本或生成日期';
COMMENT ON COLUMN dictionary_source_release.source_url IS '取得原始数据的官方地址';
COMMENT ON COLUMN dictionary_source_release.source_sha256 IS '原始下载文件的 SHA-256';
COMMENT ON COLUMN dictionary_source_release.license_name IS '该版本数据采用的许可名称';
COMMENT ON COLUMN dictionary_source_release.entry_count IS '此次初始化 SQL 中可安装的词条数量';
COMMENT ON COLUMN dictionary_source_release.imported_at IS '初始化 SQL 首次成功执行的数据库时间';
