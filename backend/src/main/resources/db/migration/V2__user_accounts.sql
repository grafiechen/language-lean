CREATE TABLE user_account (
    id uuid PRIMARY KEY,
    username varchar(80) NOT NULL,
    normalized_username varchar(80) NOT NULL UNIQUE,
    email varchar(320) NOT NULL,
    normalized_email varchar(320) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    status varchar(24) NOT NULL,
    must_change_password boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE user_account_role (
    user_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    role varchar(24) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_account_role CHECK (role IN ('ADMIN', 'USER'))
);

CREATE TABLE user_login_identifier (
    normalized_identifier varchar(320) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    identifier_type varchar(24) NOT NULL,
    UNIQUE (user_id, identifier_type),
    CONSTRAINT ck_login_identifier_type CHECK (identifier_type IN ('USERNAME', 'EMAIL'))
);

COMMENT ON TABLE user_account IS '账户主体；不保存明文密码，注销后新的同名账户使用新的 UUID';
COMMENT ON COLUMN user_account.id IS '账户全局稳定 ID，也是本地多账号缓存的归属依据';
COMMENT ON COLUMN user_account.username IS '保留用户输入大小写的展示用户名';
COMMENT ON COLUMN user_account.normalized_username IS '用于比较和唯一约束的小写规范化用户名';
COMMENT ON COLUMN user_account.email IS '保留用户输入形式的邮箱';
COMMENT ON COLUMN user_account.normalized_email IS '用于比较和唯一约束的小写规范化邮箱';
COMMENT ON COLUMN user_account.password_hash IS '带算法标识的 BCrypt 单向哈希；随机盐包含在哈希格式中';
COMMENT ON COLUMN user_account.status IS '账户是否允许登录：ACTIVE 或 DISABLED';
COMMENT ON COLUMN user_account.must_change_password IS '是否仍需修改管理员分配的初始密码';
COMMENT ON COLUMN user_account.version IS 'JPA 乐观锁版本，避免并发更新静默覆盖';
COMMENT ON COLUMN user_account.created_at IS '账户创建时间，使用带时区时间戳';
COMMENT ON COLUMN user_account.updated_at IS '账户最后更新时间，使用带时区时间戳';

COMMENT ON TABLE user_account_role IS '账户角色集合；一个账户可同时拥有 USER 和 ADMIN';
COMMENT ON COLUMN user_account_role.user_id IS '角色所属账户';
COMMENT ON COLUMN user_account_role.role IS '角色代码：ADMIN 或 USER';

COMMENT ON TABLE user_login_identifier IS '全局登录标识命名空间，防止用户名与其他账户邮箱发生歧义';
COMMENT ON COLUMN user_login_identifier.normalized_identifier IS '规范化用户名或邮箱，全表唯一';
COMMENT ON COLUMN user_login_identifier.user_id IS '该登录标识指向的账户';
COMMENT ON COLUMN user_login_identifier.identifier_type IS '标识类型：USERNAME 或 EMAIL';
