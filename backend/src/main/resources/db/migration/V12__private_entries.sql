CREATE TABLE personal_custom_entry (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    language_code varchar(16) NOT NULL REFERENCES language_config(code),
    script_code varchar(4) NOT NULL,
    written varchar(200) NOT NULL,
    normalized_written_key varchar(200) NOT NULL,
    content_json text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_private_entry UNIQUE(user_id, language_code, script_code, normalized_written_key),
    CONSTRAINT uq_private_entry_owner UNIQUE(id, user_id)
);
ALTER TABLE user_learning_item ADD CONSTRAINT fk_learning_private_owner FOREIGN KEY(personal_custom_entry_id, user_id)
    REFERENCES personal_custom_entry(id, user_id) ON DELETE CASCADE;
CREATE TABLE private_entry_change (
    id uuid PRIMARY KEY,
    entry_id uuid NOT NULL REFERENCES personal_custom_entry(id) ON DELETE CASCADE,
    edit_version bigint NOT NULL,
    changed_at timestamp with time zone NOT NULL,
    CONSTRAINT uq_private_entry_change UNIQUE(entry_id, edit_version)
);
ALTER TABLE user_learning_item ADD CONSTRAINT uq_learning_private UNIQUE(user_id, personal_custom_entry_id);
ALTER TABLE audio_asset ALTER COLUMN dictionary_entry_id DROP NOT NULL;
ALTER TABLE audio_asset ADD COLUMN personal_custom_entry_id uuid REFERENCES personal_custom_entry(id) ON DELETE CASCADE;
ALTER TABLE audio_asset DROP CONSTRAINT IF EXISTS audio_asset_content_scope_check;
ALTER TABLE audio_asset ADD CONSTRAINT ck_audio_source CHECK (
    (dictionary_entry_id IS NOT NULL AND personal_custom_entry_id IS NULL AND content_scope IN ('PUBLISHED','DRAFT')) OR
    (dictionary_entry_id IS NULL AND personal_custom_entry_id IS NOT NULL AND content_scope = 'PERSONAL')
);
ALTER TABLE audio_asset ADD CONSTRAINT uq_audio_private_resource UNIQUE(personal_custom_entry_id, content_scope, kind, resource_id);
CREATE TABLE audio_object_cleanup (
    id uuid PRIMARY KEY,
    object_key varchar(240) NOT NULL UNIQUE,
    created_at timestamp with time zone NOT NULL
);
CREATE INDEX private_entry_user_created ON personal_custom_entry(user_id, created_at, id);
COMMENT ON TABLE personal_custom_entry IS '独立私有词条，只对所属账户可见，不占用公开词典唯一键';
COMMENT ON COLUMN personal_custom_entry.id IS '全局稳定 UUID，后续审核公开通过映射关联，不能作为访问授权';
COMMENT ON COLUMN personal_custom_entry.user_id IS '归属账户，注销级联删除';
COMMENT ON COLUMN personal_custom_entry.language_code IS '创建时确定的语言，修改内容不切换语言或复习算法配置';
COMMENT ON COLUMN personal_custom_entry.script_code IS 'ISO15924书写系统代码，创建后保持不变';
COMMENT ON COLUMN personal_custom_entry.written IS '用户填写的词条写法，可以只有写法而没有读音释义';
COMMENT ON COLUMN personal_custom_entry.normalized_written_key IS 'NFKC规范化去重键，唯一范围限定在本账户';
COMMENT ON COLUMN personal_custom_entry.content_json IS '与基准内容相同的版本化读音、词义、例句契约，允许不完整';
COMMENT ON COLUMN personal_custom_entry.created_at IS '创建服务器时间';
COMMENT ON COLUMN personal_custom_entry.updated_at IS '最近保存服务器时间';
COMMENT ON COLUMN personal_custom_entry.version IS 'JPA编辑版本，旧表单不得覆盖';
COMMENT ON TABLE private_entry_change IS '私有录入和修改的审计元信息，不复制正文，删除时级联清除';
COMMENT ON COLUMN private_entry_change.id IS '审计UUID';
COMMENT ON COLUMN private_entry_change.entry_id IS '归属私有词条';
COMMENT ON COLUMN private_entry_change.edit_version IS '本次成功保存的编辑版本';
COMMENT ON COLUMN private_entry_change.changed_at IS '服务器保存时间';
COMMENT ON COLUMN user_learning_item.personal_custom_entry_id IS '私有词条引用，复合外键保证词条和进度属于同一账户';
COMMENT ON COLUMN audio_asset.personal_custom_entry_id IS '私有音频词条归属，与基准词条引用二选一';
COMMENT ON COLUMN audio_asset.content_scope IS 'PUBLISHED/DRAFT/PERSONAL；个人资源不能由管理员绕过归属访问';
COMMENT ON COLUMN tts_language_setting.personal_auto_generate IS '个人保存、详情和点击生成开关，关闭后仍允许播放已有正确音频';
COMMENT ON TABLE audio_object_cleanup IS '已授权删除的私有音频对象重试任务，数据库内容立即删除，云删除成功后撤销任务';
COMMENT ON COLUMN audio_object_cleanup.id IS '任务UUID';
COMMENT ON COLUMN audio_object_cleanup.object_key IS '私有音频对象键，不保存词条正文或账户资料';
COMMENT ON COLUMN audio_object_cleanup.created_at IS '任务创建时间，网络失败保留以便重试';
