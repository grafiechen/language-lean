ALTER TABLE account_admin_audit DROP CONSTRAINT account_admin_audit_action_check;
ALTER TABLE account_admin_audit ADD CONSTRAINT account_admin_audit_action_check
    CHECK (action IN ('CREATE', 'ENABLE', 'DISABLE', 'DELETE'));
COMMENT ON COLUMN account_admin_audit.action IS 'CREATE 创建、ENABLE 启用、DISABLE 禁用、DELETE 注销；不记录个人正文和凭据';
ALTER TABLE dictionary_revision ADD COLUMN contributor_deleted boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN dictionary_revision.contributor_deleted IS '贡献者账号注销标记；公开来源和许可保留，身份展示为已注销用户';
