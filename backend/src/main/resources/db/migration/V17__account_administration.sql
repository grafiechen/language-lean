ALTER TABLE user_account ADD COLUMN security_version bigint NOT NULL DEFAULT 0;
COMMENT ON COLUMN user_account.security_version IS '在线凭据代数；改密或状态变更递增，旧会话和旧重置链接永久失效';

CREATE TABLE account_admin_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    target_id uuid NOT NULL,
    action varchar(24) NOT NULL CHECK (action IN ('CREATE', 'ENABLE', 'DISABLE')),
    performed_at timestamp with time zone NOT NULL
);
COMMENT ON TABLE account_admin_audit IS '后台账号操作记录；仅记录稳定 ID 和动作，不记录密码、邮件或学习内容';
COMMENT ON COLUMN account_admin_audit.id IS '操作记录唯一 ID';
COMMENT ON COLUMN account_admin_audit.actor_id IS '执行操作的管理员 ID；不设置外键以保留注销后的审计';
COMMENT ON COLUMN account_admin_audit.target_id IS '被操作的账号 ID';
COMMENT ON COLUMN account_admin_audit.action IS 'CREATE 创建、ENABLE 启用、DISABLE 禁用';
COMMENT ON COLUMN account_admin_audit.performed_at IS '服务器执行操作的时间';
CREATE INDEX account_admin_audit_target_idx ON account_admin_audit (target_id, performed_at);

CREATE TABLE account_password_reset (
    token_hash varchar(64) PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    password_fingerprint varchar(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL
);
COMMENT ON TABLE account_password_reset IS '一次性密码重置凭据；仅存随机令牌的 SHA-256 摘要，成功使用后删除';
COMMENT ON COLUMN account_password_reset.token_hash IS '邮件随机令牌的 SHA-256 摘要，不保存原始链接';
COMMENT ON COLUMN account_password_reset.account_id IS '重置凭据所属账号';
COMMENT ON COLUMN account_password_reset.password_fingerprint IS '签发时密码哈希及凭据代数的摘要；改密或状态变更后旧链接失效';
COMMENT ON COLUMN account_password_reset.created_at IS '签发时间，限制同账号重复发送';
COMMENT ON COLUMN account_password_reset.expires_at IS '有效期截止时间；签发后 30 分钟';
CREATE INDEX account_password_reset_account_idx ON account_password_reset (account_id);
