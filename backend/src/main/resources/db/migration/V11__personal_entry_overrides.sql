CREATE TABLE personal_entry_override (
    learning_item_id uuid PRIMARY KEY REFERENCES user_learning_item(id) ON DELETE CASCADE,
    meaning_override varchar(4000),
    notes varchar(10000) NOT NULL DEFAULT '',
    tags_json text NOT NULL DEFAULT '[]',
    content_revision bigint NOT NULL CHECK (content_revision > 0),
    updated_at timestamp with time zone NOT NULL
);

CREATE TABLE personal_entry_override_change (
    id uuid PRIMARY KEY,
    learning_item_id uuid NOT NULL REFERENCES user_learning_item(id) ON DELETE CASCADE,
    content_revision bigint NOT NULL,
    changed_at timestamp with time zone NOT NULL,
    CONSTRAINT uq_personal_override_change UNIQUE (learning_item_id, content_revision)
);

COMMENT ON TABLE personal_entry_override IS '按共享学习身份保存个人释义、笔记及标签；不修改公开词典，最后关联删除时级联清除';
COMMENT ON COLUMN personal_entry_override.learning_item_id IS '所属学习条目；访问权限始终由其 user_id 校验';
COMMENT ON COLUMN personal_entry_override.meaning_override IS '空值表示沿用最新公开释义，非空表示个人整体释义；不改变公共读音或例句';
COMMENT ON COLUMN personal_entry_override.notes IS '私有学习笔记，不随公开词典展示或音频生成';
COMMENT ON COLUMN personal_entry_override.tags_json IS '去空、去重后的个人标签 JSON 数组，最多20个、每个50字';
COMMENT ON COLUMN personal_entry_override.content_revision IS '个人内容版本；清空后仍递增，防止旧表单覆盖；独立于复习版本和重置代际';
COMMENT ON COLUMN personal_entry_override.updated_at IS '最后一次成功保存个人内容的服务器时间';
COMMENT ON TABLE personal_entry_override_change IS '个人内容修改审计元信息，不保存释义、笔记或标签正文，随学习身份删除';
COMMENT ON COLUMN personal_entry_override_change.id IS '修改记录的稳定 UUID';
COMMENT ON COLUMN personal_entry_override_change.learning_item_id IS '修改所属学习身份，账号归属通过学习条目确定';
COMMENT ON COLUMN personal_entry_override_change.content_revision IS '本次保存产生的个人内容版本';
COMMENT ON COLUMN personal_entry_override_change.changed_at IS '保存成功的服务器时间';
