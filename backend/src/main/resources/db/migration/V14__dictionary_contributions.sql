CREATE TABLE dictionary_contribution_submission (
    id uuid PRIMARY KEY,
    submitted_by uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    private_entry_id uuid REFERENCES personal_custom_entry(id) ON DELETE SET NULL,
    learning_item_id uuid REFERENCES user_learning_item(id) ON DELETE SET NULL,
    source_id uuid NOT NULL,
    source_kind varchar(16) NOT NULL CHECK(source_kind IN ('PRIVATE','LEARNING')),
    source_version bigint NOT NULL,
    target_entry_id uuid REFERENCES dictionary_entry(id),
    base_revision integer,
    kind varchar(16) NOT NULL CHECK(kind IN ('NEW_ENTRY','SUPPLEMENT','REVISION')),
    language_code varchar(16) NOT NULL REFERENCES language_config(code),
    script_code varchar(4) NOT NULL,
    written varchar(200) NOT NULL,
    normalized_written_key varchar(200) NOT NULL,
    content_json text NOT NULL,
    base_content_json text,
    request_hash varchar(64) NOT NULL,
    status varchar(24) NOT NULL CHECK(status IN ('PENDING_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
    publish_requested boolean NOT NULL,
    submit_note varchar(500) NOT NULL,
    review_note varchar(500) NOT NULL,
    reviewed_by uuid REFERENCES user_account(id) ON DELETE SET NULL,
    published_entry_id uuid REFERENCES dictionary_entry(id),
    published_revision integer,
    active_source_key varchar(120) UNIQUE,
    created_at timestamp with time zone NOT NULL,
    reviewed_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0
);
CREATE INDEX contribution_owner_created ON dictionary_contribution_submission(submitted_by, created_at, id);
CREATE INDEX contribution_status_created ON dictionary_contribution_submission(status, created_at, id);
CREATE INDEX contribution_publication ON dictionary_contribution_submission(published_entry_id, published_revision);
ALTER TABLE dictionary_revision ADD COLUMN contribution_id uuid;
ALTER TABLE dictionary_revision ADD COLUMN contributed_by uuid;
ALTER TABLE dictionary_revision ADD COLUMN contribution_source varchar(200);
ALTER TABLE dictionary_revision ADD COLUMN contribution_license varchar(200);
COMMENT ON COLUMN dictionary_entry.created_by IS '创建人UUID，可为管理员或投稿用户，公开审计不随账户注销级联删除';
COMMENT ON TABLE dictionary_contribution_submission IS '独立投稿快照，只允许本人或审核管理员读取，不包含笔记标签或学习记录';
COMMENT ON COLUMN dictionary_contribution_submission.id IS '客户端一次投稿生成的幂等UUID，重复网络请求不能产生第二份申请';
COMMENT ON COLUMN dictionary_contribution_submission.submitted_by IS '会话确定的投稿人，管理员仅能看该用户明确提交的正文';
COMMENT ON COLUMN dictionary_contribution_submission.private_entry_id IS '存活的私有来源；删除来源时未公开申请清除，已公开申请引用置空';
COMMENT ON COLUMN dictionary_contribution_submission.learning_item_id IS '存活的学习来源；最后关联删除时未公开申请清除，已公开申请引用置空';
COMMENT ON COLUMN dictionary_contribution_submission.source_id IS '提交时的来源UUID，公开后保留出处元信息，不自动迁移学习身份';
COMMENT ON COLUMN dictionary_contribution_submission.source_kind IS 'PRIVATE私有词条投稿，LEARNING公开词条个人修订';
COMMENT ON COLUMN dictionary_contribution_submission.source_version IS '提交时的私人编辑或个人覆盖版本';
COMMENT ON COLUMN dictionary_contribution_submission.target_entry_id IS '同写法已公开条目或修订目标，未通过审核不能改写基准';
COMMENT ON COLUMN dictionary_contribution_submission.base_revision IS '审核所依据的公开版本，变化后不能覆盖，应重新提交';
COMMENT ON COLUMN dictionary_contribution_submission.kind IS 'NEW_ENTRY新条目，SUPPLEMENT保留原文追加补充，REVISION明确替换公开版本';
COMMENT ON COLUMN dictionary_contribution_submission.language_code IS '提交来源确定的语言';
COMMENT ON COLUMN dictionary_contribution_submission.script_code IS '提交来源确定的ISO15924书写系统';
COMMENT ON COLUMN dictionary_contribution_submission.written IS '提交时固定写法';
COMMENT ON COLUMN dictionary_contribution_submission.normalized_written_key IS 'NFKC公开唯一身份预检与审核复查键';
COMMENT ON COLUMN dictionary_contribution_submission.content_json IS '用户明确选择提交的正文及来源许可快照，审核期间不随个人编辑变化';
COMMENT ON COLUMN dictionary_contribution_submission.base_content_json IS '投稿时的公开基准快照，用于审核对照，不随其他发布改变';
COMMENT ON COLUMN dictionary_contribution_submission.request_hash IS '原请求SHA256，防止同幂等UUID被不同内容重复使用';
COMMENT ON COLUMN dictionary_contribution_submission.status IS '待审、公开通过、拒绝或本人撤回';
COMMENT ON COLUMN dictionary_contribution_submission.publish_requested IS '本人明确申请审核通过后公开本次快照';
COMMENT ON COLUMN dictionary_contribution_submission.submit_note IS '投稿说明，不从私人学习笔记自动复制';
COMMENT ON COLUMN dictionary_contribution_submission.review_note IS '审核意见，拒绝和撤回不改写公开词典';
COMMENT ON COLUMN dictionary_contribution_submission.reviewed_by IS '执行终态操作的用户，审核入口单独限制管理员';
COMMENT ON COLUMN dictionary_contribution_submission.published_entry_id IS '审核实际发布的稳定基准身份';
COMMENT ON COLUMN dictionary_contribution_submission.published_revision IS '实际追加的线性发布版本';
COMMENT ON COLUMN dictionary_contribution_submission.active_source_key IS '待审期间每账号来源唯一占位，结束后清空以允许下一次提交';
COMMENT ON COLUMN dictionary_contribution_submission.created_at IS '服务器投稿时间';
COMMENT ON COLUMN dictionary_contribution_submission.reviewed_at IS '审核或撤回时间';
COMMENT ON COLUMN dictionary_contribution_submission.version IS 'JPA审核版本，陈旧决定不能覆写另一审核结果';
COMMENT ON COLUMN dictionary_revision.contribution_id IS '来源申请UUID，无级联外键，公开历史独立保留';
COMMENT ON COLUMN dictionary_revision.contributed_by IS '发布时贡献者UUID快照，审核发布人仍另行保存';
COMMENT ON COLUMN dictionary_revision.contribution_source IS '审核通过时的贡献来源，私人来源和投稿账号删除后仍可归属公开历史';
COMMENT ON COLUMN dictionary_revision.contribution_license IS '审核通过时的贡献许可，不覆盖基准许可，账号注销也不丢失';
