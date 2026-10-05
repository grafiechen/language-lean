CREATE TABLE tts_language_setting (
    language_code VARCHAR(16) PRIMARY KEY REFERENCES language_config(code),
    provider VARCHAR(32) NOT NULL DEFAULT 'GOOGLE',
    model VARCHAR(32) NOT NULL DEFAULT 'Chirp3-HD',
    voice VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    personal_auto_generate BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0
);
INSERT INTO tts_language_setting(language_code, voice) VALUES ('ja', 'ja-JP-Chirp3-HD-Aoede');
COMMENT ON TABLE tts_language_setting IS '每语言的非敏感 TTS 配置；云凭据仅由服务端环境或 ADC 提供';
COMMENT ON COLUMN tts_language_setting.language_code IS '稳定语言代码，发音 locale 复用语言配置';
COMMENT ON COLUMN tts_language_setting.provider IS '服务商，首版 GOOGLE';
COMMENT ON COLUMN tts_language_setting.model IS '模型，首版 Chirp3-HD';
COMMENT ON COLUMN tts_language_setting.voice IS '固定默认声音全名，例如 ja-JP-Chirp3-HD-Aoede';
COMMENT ON COLUMN tts_language_setting.enabled IS '是否允许该语言生成新的音频，已有音频仍可播放';
COMMENT ON COLUMN tts_language_setting.personal_auto_generate IS '预留个人词条自动生成开关；个人词条模块尚未接入';
COMMENT ON COLUMN tts_language_setting.version IS '后台编辑乐观锁版本';

CREATE TABLE audio_asset (
    id UUID PRIMARY KEY,
    dictionary_entry_id UUID NOT NULL REFERENCES dictionary_entry(id) ON DELETE CASCADE,
    content_scope VARCHAR(16) NOT NULL CHECK(content_scope IN ('PUBLISHED', 'DRAFT')),
    kind VARCHAR(16) NOT NULL CHECK(kind IN ('WORD', 'EXAMPLE')),
    resource_id UUID NOT NULL,
    current_version_id UUID,
    generation_token UUID,
    generation_fingerprint VARCHAR(64),
    lease_until TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_audio_resource UNIQUE(dictionary_entry_id, content_scope, kind, resource_id)
);
CREATE TABLE audio_version (
    id UUID PRIMARY KEY,
    audio_asset_id UUID NOT NULL REFERENCES audio_asset(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(32) NOT NULL,
    voice VARCHAR(120) NOT NULL,
    pronunciation_locale VARCHAR(35) NOT NULL,
    text_hash VARCHAR(64) NOT NULL,
    generation_fingerprint VARCHAR(64) NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    object_key VARCHAR(240) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_audio_version UNIQUE(audio_asset_id, version_number)
);
ALTER TABLE audio_asset ADD CONSTRAINT fk_audio_current_version FOREIGN KEY(current_version_id) REFERENCES audio_version(id);
COMMENT ON TABLE audio_asset IS '一个词条读音或例句的音频身份；草稿与公开权限范围分开';
COMMENT ON COLUMN audio_asset.dictionary_entry_id IS '音频所归属的基准词条';
COMMENT ON COLUMN audio_asset.content_scope IS '公开或管理员草稿，播放时重新验证权限及封禁状态';
COMMENT ON COLUMN audio_asset.kind IS '词条发音或例句发音';
COMMENT ON COLUMN audio_asset.resource_id IS '读音或例句的稳定 UUID';
COMMENT ON COLUMN audio_asset.current_version_id IS '仅在新文件上传成功后原子切换的可用版本';
COMMENT ON COLUMN audio_asset.generation_token IS '当前生成任务的租约标识，防止迟到工作进程覆盖新任务';
COMMENT ON COLUMN audio_asset.generation_fingerprint IS '内容、locale、服务商、模型、声音和资源权限范围的生成哈希';
COMMENT ON COLUMN audio_asset.lease_until IS '任务最长租约；异常退出后允许重新认领';
COMMENT ON COLUMN audio_asset.failure_code IS '不含凭据或上游响应正文的失败码';
COMMENT ON COLUMN audio_asset.version IS 'JPA 乐观锁版本';
COMMENT ON TABLE audio_version IS '独立不可变音频版本；数据库只保存对象元数据，不保存 MP3';
COMMENT ON COLUMN audio_version.audio_asset_id IS '此版本所归属的音频资产';
COMMENT ON COLUMN audio_version.version_number IS '同资产内线性递增的版本号';
COMMENT ON COLUMN audio_version.provider IS '生成服务商';
COMMENT ON COLUMN audio_version.model IS '生成模型';
COMMENT ON COLUMN audio_version.voice IS '本次使用的声音全名';
COMMENT ON COLUMN audio_version.pronunciation_locale IS '本次使用的 BCP 47 发音区域';
COMMENT ON COLUMN audio_version.text_hash IS '发音文本 SHA-256';
COMMENT ON COLUMN audio_version.generation_fingerprint IS '本次生成输入和权限范围指纹';
COMMENT ON COLUMN audio_version.file_hash IS '上传文件 SHA-256，读取时核对完整性';
COMMENT ON COLUMN audio_version.object_key IS 'R2 私有对象键，不向客户端暴露云凭据';
COMMENT ON COLUMN audio_version.created_at IS '此版本成功生成的时间';
