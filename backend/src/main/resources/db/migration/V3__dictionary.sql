CREATE TABLE dictionary_entry (
    id uuid PRIMARY KEY,
    language_code varchar(16) NOT NULL REFERENCES language_config(code),
    script_code varchar(4) NOT NULL,
    written varchar(200) NOT NULL,
    normalized_written_key varchar(200) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'BANNED')),
    current_revision integer NOT NULL DEFAULT 0,
    draft_content text,
    draft_base_revision integer,
    created_by uuid NOT NULL,
    updated_by uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (language_code, script_code, normalized_written_key)
);
CREATE TABLE dictionary_revision (
    id uuid PRIMARY KEY,
    entry_id uuid NOT NULL REFERENCES dictionary_entry(id),
    revision_number integer NOT NULL,
    content text NOT NULL,
    published_by uuid NOT NULL,
    published_at timestamp with time zone NOT NULL,
    note varchar(500) NOT NULL,
    UNIQUE (entry_id, revision_number)
);
CREATE INDEX dictionary_entry_listing ON dictionary_entry(status, language_code, written);
COMMENT ON TABLE dictionary_entry IS '基准词条稳定身份；管理员草稿与当前公开版本分开，个人词条不写此表';
COMMENT ON COLUMN dictionary_entry.id IS '全局 UUID，未来学习引用以此为身份，不依赖写法';
COMMENT ON COLUMN dictionary_entry.language_code IS '词条语言；禁用语言后不进入用户词典查询';
COMMENT ON COLUMN dictionary_entry.script_code IS 'ISO 15924 书写系统代码，例如 Jpan、Latn';
COMMENT ON COLUMN dictionary_entry.written IS '固定书写形式；身份创建后不随版本修改';
COMMENT ON COLUMN dictionary_entry.normalized_written_key IS 'NFKC 规范化并去除两端空白的写法，与语言和书写系统组成唯一键';
COMMENT ON COLUMN dictionary_entry.status IS 'DRAFT 尚未公开，PUBLISHED 可查询，BANNED 仅向用户返回封禁标记';
COMMENT ON COLUMN dictionary_entry.current_revision IS '当前公开版本序号；0 表示从未发布';
COMMENT ON COLUMN dictionary_entry.draft_content IS 'schemaVersion=1 的完整类型化 JSON 草稿，包含有稳定 ID 的读音、词义和例句';
COMMENT ON COLUMN dictionary_entry.draft_base_revision IS '草稿编辑所依据的公开版，发布时校验防止覆盖其他版本';
COMMENT ON COLUMN dictionary_entry.created_by IS '创建管理员 ID；审计不随账户注销级联删除';
COMMENT ON COLUMN dictionary_entry.updated_by IS '最后操作管理员 ID';
COMMENT ON COLUMN dictionary_entry.created_at IS '首次创建时间';
COMMENT ON COLUMN dictionary_entry.updated_at IS '最后保存、发布或封禁时间';
COMMENT ON COLUMN dictionary_entry.version IS 'JPA 乐观锁及客户端编辑版本，陈旧页面操作返回冲突';
COMMENT ON TABLE dictionary_revision IS '只追加的线性发布历史；恢复旧内容先复制到草稿，再追加新版本';
COMMENT ON COLUMN dictionary_revision.id IS '发布版本的稳定 UUID';
COMMENT ON COLUMN dictionary_revision.entry_id IS '所属词条';
COMMENT ON COLUMN dictionary_revision.revision_number IS '从 1 开始递增的发布序号';
COMMENT ON COLUMN dictionary_revision.content IS '完整不可改写快照，与草稿共享 schemaVersion=1 内容契约';
COMMENT ON COLUMN dictionary_revision.published_by IS '确认发布的管理员 ID';
COMMENT ON COLUMN dictionary_revision.published_at IS '服务端发布时间';
COMMENT ON COLUMN dictionary_revision.note IS '发布或纠错说明';
