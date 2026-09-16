CREATE TABLE language_config (
    code varchar(16) PRIMARY KEY,
    display_name varchar(80) NOT NULL,
    pronunciation_locale varchar(35) NOT NULL,
    enabled boolean NOT NULL DEFAULT false
);

CREATE TABLE language_review_type (
    language_code varchar(16) NOT NULL REFERENCES language_config(code),
    type_id varchar(64) NOT NULL,
    contract_version integer NOT NULL CHECK (contract_version > 0),
    enabled boolean NOT NULL DEFAULT false,
    PRIMARY KEY (language_code, type_id)
);

COMMENT ON TABLE language_config IS '后台启用的学习语言及其发音区域配置';
COMMENT ON COLUMN language_config.code IS '稳定语言代码，例如 ja；不使用页面显示名称作为关联键';
COMMENT ON COLUMN language_config.display_name IS '面向用户展示的语言名称';
COMMENT ON COLUMN language_config.pronunciation_locale IS '发音和 TTS 使用的 BCP 47 locale';
COMMENT ON COLUMN language_config.enabled IS '是否允许客户端展示并使用该语言';

COMMENT ON TABLE language_review_type IS '某种语言启用的复习题型及客户端契约版本';
COMMENT ON COLUMN language_review_type.language_code IS '所属语言，引用 language_config.code';
COMMENT ON COLUMN language_review_type.type_id IS '稳定题型代码，例如 LISTEN_RECALL';
COMMENT ON COLUMN language_review_type.contract_version IS '前后端题型协议版本，用于拒绝不兼容客户端';
COMMENT ON COLUMN language_review_type.enabled IS '是否向客户端发布此题型';

INSERT INTO language_config VALUES ('ja', '日语', 'ja-JP', true);
INSERT INTO language_review_type VALUES ('ja', 'LISTEN_RECALL', 1, true);
