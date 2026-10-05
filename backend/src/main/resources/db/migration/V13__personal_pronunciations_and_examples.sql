ALTER TABLE personal_entry_override ADD COLUMN readings_override_json text;
ALTER TABLE personal_entry_override ADD COLUMN senses_override_json text;
ALTER TABLE personal_entry_override ADD COLUMN audio_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE audio_asset ADD COLUMN learning_item_id uuid REFERENCES user_learning_item(id) ON DELETE CASCADE;
ALTER TABLE audio_asset DROP CONSTRAINT ck_audio_source;
ALTER TABLE audio_asset ADD CONSTRAINT ck_audio_source CHECK (
    (dictionary_entry_id IS NOT NULL AND personal_custom_entry_id IS NULL AND learning_item_id IS NULL AND content_scope IN ('PUBLISHED','DRAFT')) OR
    (dictionary_entry_id IS NULL AND personal_custom_entry_id IS NOT NULL AND learning_item_id IS NULL AND content_scope = 'PERSONAL') OR
    (dictionary_entry_id IS NULL AND personal_custom_entry_id IS NULL AND learning_item_id IS NOT NULL AND content_scope = 'OVERRIDE')
);
ALTER TABLE audio_asset ADD CONSTRAINT uq_audio_learning_resource UNIQUE(learning_item_id, content_scope, kind, resource_id);
COMMENT ON COLUMN personal_entry_override.readings_override_json IS '本人的完整读音列表覆盖，NULL继承基准，空数组表示明确不使用读音';
COMMENT ON COLUMN personal_entry_override.senses_override_json IS '本人的完整词义及例句覆盖，NULL继承基准，不能同时使用简单个人释义';
COMMENT ON COLUMN personal_entry_override.audio_revision IS '读音或词义例句结构变更版本，笔记标签及简单释义编辑不递增';
COMMENT ON COLUMN audio_asset.learning_item_id IS 'OVERRIDE音频引用本人学习身份，删除最后关联时级联清除';
COMMENT ON COLUMN audio_asset.content_scope IS 'PUBLISHED/DRAFT/PERSONAL/OVERRIDE分别隔离公开、草稿、私有词条及个人覆盖音频';
