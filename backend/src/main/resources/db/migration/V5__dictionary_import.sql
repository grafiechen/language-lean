ALTER TABLE dictionary_entry
    ADD COLUMN origin_type varchar(24) NOT NULL DEFAULT 'ADMIN';
ALTER TABLE dictionary_entry
    ADD CONSTRAINT ck_dictionary_entry_origin CHECK (origin_type IN ('ADMIN', 'OPEN_SOURCE', 'USER_CONTRIBUTED'));

CREATE TABLE dictionary_import_batch (
    id uuid PRIMARY KEY,
    status varchar(24) NOT NULL CHECK (status IN ('VALIDATED', 'APPLIED')),
    original_file_name varchar(255) NOT NULL,
    original_sha256 varchar(64) NOT NULL,
    source_name varchar(200) NOT NULL,
    source_version varchar(100) NOT NULL,
    license_text varchar(200) NOT NULL,
    total_count integer NOT NULL,
    ready_count integer NOT NULL,
    duplicate_count integer NOT NULL,
    invalid_count integer NOT NULL,
    imported_count integer NOT NULL DEFAULT 0,
    apply_duplicate_count integer NOT NULL DEFAULT 0,
    normalized_payload text NOT NULL,
    validation_report text NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    applied_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX dictionary_import_batch_created_at ON dictionary_import_batch(created_at DESC);

COMMENT ON COLUMN dictionary_entry.origin_type IS '词条进入基准词典的来源类型：后台录入、开源导入或审核后的用户贡献';
COMMENT ON TABLE dictionary_import_batch IS '系统词典导入暂存批次；校验预览与确认发布分开执行';
COMMENT ON COLUMN dictionary_import_batch.original_sha256 IS '上传文件的 SHA-256，用于确认实际导入来源和重复操作排查';
COMMENT ON COLUMN dictionary_import_batch.normalized_payload IS '通过校验且等待导入的规范化 schemaVersion=1 词条快照';
COMMENT ON COLUMN dictionary_import_batch.validation_report IS '上传文件每一行的 READY、DUPLICATE 或 INVALID 校验结果';
COMMENT ON COLUMN dictionary_import_batch.apply_duplicate_count IS '预览后、确认前新出现而在确认阶段跳过的已存在词条数';
COMMENT ON COLUMN dictionary_import_batch.created_by IS '创建导入批次的管理员稳定账户 ID';
COMMENT ON COLUMN dictionary_import_batch.version IS '确认导入时使用的 JPA 乐观锁版本';
