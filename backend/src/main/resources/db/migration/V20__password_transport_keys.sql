CREATE TABLE password_transport_key (
    id uuid PRIMARY KEY,
    session_hash varchar(64) NOT NULL,
    purpose varchar(120) NOT NULL,
    wrapped_key varchar(128) NOT NULL,
    expires_at timestamp with time zone NOT NULL
);
CREATE INDEX password_transport_key_expiry ON password_transport_key(expires_at);
CREATE INDEX password_transport_key_session ON password_transport_key(session_hash);
COMMENT ON TABLE password_transport_key IS '密码请求一次性密钥；使用即删除，过期定时清理，不存密码或明文密钥';
COMMENT ON COLUMN password_transport_key.id IS '随机密钥标识，也是 AES-GCM 认证附加数据的一部分';
COMMENT ON COLUMN password_transport_key.session_hash IS '所属 Session ID 的 SHA-256 摘要，不存会话 Cookie';
COMMENT ON COLUMN password_transport_key.purpose IS '允许使用该密钥的具体 POST 接口路径';
COMMENT ON COLUMN password_transport_key.wrapped_key IS '服务器主密钥 AES-256-GCM 加密的临时密钥，Base64(12字节IV+密文+认证标签)';
COMMENT ON COLUMN password_transport_key.expires_at IS '服务端有效期截止时刻，客户端时间不参与判断';
