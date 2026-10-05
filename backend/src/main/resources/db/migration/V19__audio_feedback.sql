CREATE TABLE audio_feedback (
    id uuid PRIMARY KEY,
    submitted_by uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    dictionary_entry_id uuid NOT NULL REFERENCES dictionary_entry(id) ON DELETE CASCADE,
    resource_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('WORD', 'EXAMPLE')),
    category varchar(32) NOT NULL CHECK (category IN ('WRONG_PRONUNCIATION', 'UNPLAYABLE')),
    reported_revision integer NOT NULL,
    reported_audio_version_id uuid,
    baseline_audio_version_id uuid,
    pronunciation_text text NOT NULL,
    description varchar(1000) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
    resolution_note varchar(1000) NOT NULL DEFAULT '',
    reviewed_by uuid,
    created_at timestamp with time zone NOT NULL,
    reviewed_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX audio_feedback_admin_queue ON audio_feedback(status, created_at DESC, id);
CREATE INDEX audio_feedback_owner ON audio_feedback(submitted_by, created_at DESC, id);
COMMENT ON TABLE audio_feedback IS '用户明确提交的公共发音问题；注销账号时删除，不收集私人读音或学习记录';
COMMENT ON COLUMN audio_feedback.id IS '客户端生成的幂等提交标识，网络重试复用';
COMMENT ON COLUMN audio_feedback.submitted_by IS '反馈归属账号，普通用户只能查看本人反馈';
COMMENT ON COLUMN audio_feedback.dictionary_entry_id IS '反馈对应的基准词条';
COMMENT ON COLUMN audio_feedback.resource_id IS '公开读音或例句的稳定子项标识';
COMMENT ON COLUMN audio_feedback.kind IS 'WORD 词条发音、EXAMPLE 例句发音';
COMMENT ON COLUMN audio_feedback.category IS '读音错误或无法播放';
COMMENT ON COLUMN audio_feedback.reported_revision IS '提交时的公开词典版本，不涉及未公开草稿';
COMMENT ON COLUMN audio_feedback.reported_audio_version_id IS '用户实际遇到的公开音频版本，可为空；仅用于追踪，不包含对象存储键';
COMMENT ON COLUMN audio_feedback.baseline_audio_version_id IS '服务器收件时的公共音频基准，用来防止将未更新的旧音频标记为已修复';
COMMENT ON COLUMN audio_feedback.pronunciation_text IS '提交时已核对的公开发音文本快照';
COMMENT ON COLUMN audio_feedback.description IS '用户自愿提交的问题说明，最多1000字';
COMMENT ON COLUMN audio_feedback.status IS 'PENDING 待处理、RESOLVED 已修复、DISMISSED 无需修复';
COMMENT ON COLUMN audio_feedback.resolution_note IS '管理员处理说明，用户可查看';
COMMENT ON COLUMN audio_feedback.reviewed_by IS '处理人不透明账号标识，不保留个人联系方式';
COMMENT ON COLUMN audio_feedback.created_at IS '服务器接收反馈的时间';
COMMENT ON COLUMN audio_feedback.reviewed_at IS '管理员完成处理的时间';
COMMENT ON COLUMN audio_feedback.version IS '审核表单乐观锁版本，避免重复或陈旧处理';
