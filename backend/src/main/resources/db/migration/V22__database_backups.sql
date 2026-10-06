CREATE TABLE database_backup_job (
    id uuid PRIMARY KEY,
    actor_id uuid,
    trigger_kind varchar(16) NOT NULL CHECK(trigger_kind IN ('MANUAL', 'SCHEDULED')),
    state varchar(16) NOT NULL CHECK(state IN ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    created_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    run_token uuid,
    error_code varchar(64)
);
CREATE INDEX database_backup_job_queue ON database_backup_job(state, created_at);
CREATE TABLE database_backup_control (
    id varchar(16) PRIMARY KEY,
    active_job uuid,
    lease_until timestamp with time zone,
    next_scheduled_at timestamp with time zone
);
INSERT INTO database_backup_control(id) VALUES ('DATABASE');
CREATE TABLE database_backup_object (
    job_id uuid PRIMARY KEY REFERENCES database_backup_job(id),
    object_key varchar(240) NOT NULL UNIQUE,
    storage_scope varchar(64) NOT NULL,
    encryption_key_id varchar(80) NOT NULL,
    encrypted_bytes bigint NOT NULL CHECK(encrypted_bytes > 0),
    sha256 varchar(64) NOT NULL,
    state varchar(24) NOT NULL CHECK(state IN ('UPLOADING', 'VERIFIED', 'DELETE_PENDING', 'DELETED')),
    verified_at timestamp with time zone,
    deleted_at timestamp with time zone
);
COMMENT ON TABLE database_backup_job IS '服务器数据库灾备任务，不提供个人导出、明文数据库下载或凭据';
COMMENT ON COLUMN database_backup_job.id IS '管理员请求幂等标识或定时任务随机标识';
COMMENT ON COLUMN database_backup_job.actor_id IS '手动触发管理员UUID，定时任务为空，不随注销删除执行记录';
COMMENT ON COLUMN database_backup_job.trigger_kind IS 'MANUAL管理员触发，SCHEDULED定时触发';
COMMENT ON COLUMN database_backup_job.state IS 'QUEUED排队，RUNNING执行，SUCCESS完整上传并回读校验，FAILED失败或租约过期';
COMMENT ON COLUMN database_backup_job.created_at IS '任务创建时间';
COMMENT ON COLUMN database_backup_job.started_at IS '认领执行时间';
COMMENT ON COLUMN database_backup_job.finished_at IS '成功或失败确认时间';
COMMENT ON COLUMN database_backup_job.run_token IS '防止过期工作者修改任务的认领令牌，不返回客户端';
COMMENT ON COLUMN database_backup_job.error_code IS '不含连接地址、用户名、密码、对象键或异常正文的固定错误码';
COMMENT ON TABLE database_backup_control IS '单行行锁控制跨进程备份串行及定时频率';
COMMENT ON COLUMN database_backup_control.id IS '固定DATABASE';
COMMENT ON COLUMN database_backup_control.active_job IS '当前执行任务，不允许并发转储';
COMMENT ON COLUMN database_backup_control.lease_until IS '中断任务的认领截止时间';
COMMENT ON COLUMN database_backup_control.next_scheduled_at IS '下一次定时备份时间，间隔为0则不执行定时备份';
COMMENT ON TABLE database_backup_object IS '私有加密备份的元数据和清理任务；不存任何明文解密密钥';
COMMENT ON COLUMN database_backup_object.job_id IS '对应灾备任务';
COMMENT ON COLUMN database_backup_object.object_key IS '仅服务端使用的私有对象键，不在管理响应中返回';
COMMENT ON COLUMN database_backup_object.storage_scope IS '存储账号及桶标识的摘要，防止切换桶后误清理';
COMMENT ON COLUMN database_backup_object.encryption_key_id IS '非秘密的密钥版本标识，用于运维选取历史解密密钥，不能代替实际密钥';
COMMENT ON COLUMN database_backup_object.encrypted_bytes IS '加密归档的总字节数';
COMMENT ON COLUMN database_backup_object.sha256 IS '加密归档的SHA256，完整云回读后核对';
COMMENT ON COLUMN database_backup_object.state IS 'UPLOADING上传中，VERIFIED已回读核验，DELETE_PENDING待删除，DELETED已删除';
COMMENT ON COLUMN database_backup_object.verified_at IS '云回读一致性校验通过时间';
COMMENT ON COLUMN database_backup_object.deleted_at IS '确认清理时间，执行记录仍保留';
