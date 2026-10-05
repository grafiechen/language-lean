ALTER TABLE user_learning_item ADD COLUMN last_review_event_id UUID;
ALTER TABLE user_learning_item ADD COLUMN automatic_ear_focus BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN user_learning_item.last_review_event_id IS '当前进度对应的完成事件；同一答题时间时用事件 UUID 排序';
COMMENT ON COLUMN user_learning_item.automatic_ear_focus IS '出现 Again 或 Hard 后自动标记的听力重点；完整重置时清空';
COMMENT ON COLUMN user_learning_item.fsrs_state IS '官方 FSRS 卡片状态 JSON，含稳定性、难度、阶段及下次复习时间';

CREATE TABLE learning_review_event (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    learning_item_id UUID NOT NULL REFERENCES user_learning_item(id) ON DELETE CASCADE,
    attempt_id UUID NOT NULL,
    progress_epoch UUID NOT NULL,
    base_version VARCHAR(64) NOT NULL,
    base_event_id UUID,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    final_rating VARCHAR(16) NOT NULL CHECK (final_rating IN ('AGAIN', 'HARD', 'GOOD')),
    submission_json TEXT NOT NULL,
    state_before TEXT NOT NULL,
    state_after TEXT NOT NULL,
    scheduler_configuration TEXT NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    next_review_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_learning_review_attempt UNIQUE (user_id, attempt_id)
);
CREATE INDEX ix_learning_review_history ON learning_review_event(learning_item_id, completed_at);
COMMENT ON TABLE learning_review_event IS '一次词条全部题型完成后的不可变复习事件；上传重试幂等，删除学习条目时级联清空';
COMMENT ON COLUMN learning_review_event.id IS '客户端生成的稳定事件 UUID，自动与手动上传复用';
COMMENT ON COLUMN learning_review_event.user_id IS '从认证会话取得的账户归属';
COMMENT ON COLUMN learning_review_event.learning_item_id IS '跨单词本共享的用户学习身份';
COMMENT ON COLUMN learning_review_event.attempt_id IS '本次词条训练身份，避免更换事件 UUID 后重复计分';
COMMENT ON COLUMN learning_review_event.progress_epoch IS '学习进度代际，用于拒绝重置之前的离线事件';
COMMENT ON COLUMN learning_review_event.base_version IS '开始训练时的进度时间戳，初始为 0';
COMMENT ON COLUMN learning_review_event.base_event_id IS '基准事件 UUID，消除相同时间戳下的版本歧义';
COMMENT ON COLUMN learning_review_event.completed_at IS '实际完成答题时间；决定进度的新旧，与接收时间无关';
COMMENT ON COLUMN learning_review_event.received_at IS '服务器接收时间，仅用于审计';
COMMENT ON COLUMN learning_review_event.final_rating IS '所有题型全部尝试的最差评分';
COMMENT ON COLUMN learning_review_event.submission_json IS '完整版本化提交契约，包含题型协议和重试记录';
COMMENT ON COLUMN learning_review_event.state_before IS '客户端引用的基准 FSRS 卡片快照，初始为 {}';
COMMENT ON COLUMN learning_review_event.state_after IS '按实际完成时间计算的 FSRS 卡片快照';
COMMENT ON COLUMN learning_review_event.scheduler_configuration IS '生成此快照所使用的 FSRS 参数';
COMMENT ON COLUMN learning_review_event.algorithm_version IS '调度算法及库版本';
COMMENT ON COLUMN learning_review_event.next_review_at IS '此次事件计算的下次复习时间，迟到事件不覆盖较新进度';
