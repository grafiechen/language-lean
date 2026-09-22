ALTER TABLE language_config ADD COLUMN version bigint NOT NULL DEFAULT 0;
COMMENT ON COLUMN language_config.version IS '语言配置乐观锁版本，防止多个管理页面覆盖彼此的配置';
