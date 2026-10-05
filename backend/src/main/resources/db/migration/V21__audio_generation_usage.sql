CREATE TABLE audio_generation_usage (
    id uuid PRIMARY KEY,
    requested_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    provider varchar(32) NOT NULL,
    model varchar(32) NOT NULL,
    input_characters integer NOT NULL CHECK (input_characters >= 0),
    response_bytes bigint CHECK (response_bytes >= 0),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('REQUESTED', 'READY', 'FAILED', 'DISCARDED')),
    CHECK ((outcome = 'REQUESTED' AND completed_at IS NULL) OR (outcome <> 'REQUESTED' AND completed_at IS NOT NULL))
);
CREATE INDEX audio_generation_usage_month ON audio_generation_usage(requested_at);
COMMENT ON TABLE audio_generation_usage IS '匿名音频生成用量，不存用户、词条、文本、对象键或凭据；供管理员按月汇总，不能推算账单';
COMMENT ON COLUMN audio_generation_usage.id IS '每次实际进入合成适配器的随机统计标识，不关联学习身份';
COMMENT ON COLUMN audio_generation_usage.requested_at IS '进入合成适配器之前的服务器时间；按UTC自然月归属';
COMMENT ON COLUMN audio_generation_usage.completed_at IS '应用最终确认结果的服务器时间；进程中断时可为空';
COMMENT ON COLUMN audio_generation_usage.provider IS '请求的服务商标识';
COMMENT ON COLUMN audio_generation_usage.model IS '请求的模型标识';
COMMENT ON COLUMN audio_generation_usage.input_characters IS '发音文本的Unicode码点数，不保存原文，不作为服务商计费依据';
COMMENT ON COLUMN audio_generation_usage.response_bytes IS '适配器返回的音频字节数；未返回有效结果时为空';
COMMENT ON COLUMN audio_generation_usage.outcome IS 'REQUESTED结果未确认、READY已切换可用版本、FAILED生成或上传失败、DISCARDED内容变化或旧任务被替代';
