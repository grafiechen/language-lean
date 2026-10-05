CREATE TABLE system_dictionary (
    code varchar(64) PRIMARY KEY,
    display_name varchar(80) NOT NULL,
    description varchar(500) NOT NULL DEFAULT '',
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE system_dictionary_item (
    id uuid PRIMARY KEY,
    dictionary_code varchar(64) NOT NULL REFERENCES system_dictionary(code),
    item_value varchar(200) NOT NULL,
    display_name varchar(200) NOT NULL,
    description varchar(500) NOT NULL DEFAULT '',
    sort_order integer NOT NULL DEFAULT 0,
    enabled boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_system_dictionary_item_value UNIQUE (dictionary_code, item_value)
);

CREATE INDEX system_dictionary_item_listing
    ON system_dictionary_item(dictionary_code, enabled, sort_order, display_name);

COMMENT ON TABLE system_dictionary IS '后台维护的通用下拉字典；字典代码是程序引用的稳定身份';
COMMENT ON COLUMN system_dictionary.code IS '稳定字典代码，例如 CONTENT_SOURCE；创建后不可修改';
COMMENT ON COLUMN system_dictionary.display_name IS '后台展示名称';
COMMENT ON COLUMN system_dictionary.description IS '说明该字典在系统中的用途';
COMMENT ON COLUMN system_dictionary.enabled IS '停用后不再向新编辑表单提供选项，历史快照不受影响';
COMMENT ON COLUMN system_dictionary.version IS 'JPA 乐观锁版本，防止多个管理页面互相覆盖';
COMMENT ON TABLE system_dictionary_item IS '系统字典中的可排序选项；停用代替删除以保留历史含义';
COMMENT ON COLUMN system_dictionary_item.id IS '选项稳定 UUID';
COMMENT ON COLUMN system_dictionary_item.dictionary_code IS '所属系统字典代码';
COMMENT ON COLUMN system_dictionary_item.item_value IS '保存到业务快照中的稳定值；创建后不可修改';
COMMENT ON COLUMN system_dictionary_item.display_name IS '下拉框向用户展示的文字';
COMMENT ON COLUMN system_dictionary_item.description IS '选项用途或来源说明';
COMMENT ON COLUMN system_dictionary_item.sort_order IS '升序排列值，小值优先';
COMMENT ON COLUMN system_dictionary_item.enabled IS '是否允许在新的编辑中选择';
COMMENT ON COLUMN system_dictionary_item.version IS 'JPA 乐观锁版本';

INSERT INTO system_dictionary(code, display_name, description, enabled, version)
VALUES ('CONTENT_SOURCE', '内容来源', '词条内容从何处取得，用于后台录入时选择并写入版本快照。', true, 0);
INSERT INTO system_dictionary_item(id, dictionary_code, item_value, display_name, description, sort_order, enabled, version)
VALUES (CAST('10000000-0000-0000-0000-000000000001' AS uuid), 'CONTENT_SOURCE', '手工录入', '手工录入', '由管理员或录入人员自行整理的内容。', 10, true, 0);
