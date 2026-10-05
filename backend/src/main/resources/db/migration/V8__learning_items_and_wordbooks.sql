CREATE TABLE wordbook (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL,
    description varchar(500) NOT NULL DEFAULT '',
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_wordbook_user_name UNIQUE (user_id, name)
);

CREATE TABLE user_learning_item (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    dictionary_entry_id uuid REFERENCES dictionary_entry(id),
    personal_custom_entry_id uuid,
    manual_ear_focus boolean NOT NULL DEFAULT false,
    progress_epoch uuid NOT NULL,
    fsrs_algorithm varchar(32) NOT NULL DEFAULT 'FSRS',
    fsrs_algorithm_version varchar(32) NOT NULL DEFAULT 'UNINITIALIZED',
    fsrs_state text NOT NULL DEFAULT '{}',
    last_reviewed_at timestamp with time zone,
    next_review_at timestamp with time zone,
    review_count integer NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    lapse_count integer NOT NULL DEFAULT 0 CHECK (lapse_count >= 0),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_learning_item_one_source CHECK (
        (dictionary_entry_id IS NOT NULL AND personal_custom_entry_id IS NULL)
        OR (dictionary_entry_id IS NULL AND personal_custom_entry_id IS NOT NULL)
    ),
    CONSTRAINT uq_learning_item_dictionary UNIQUE (user_id, dictionary_entry_id)
);

CREATE TABLE wordbook_learning_item (
    wordbook_id uuid NOT NULL REFERENCES wordbook(id) ON DELETE CASCADE,
    learning_item_id uuid NOT NULL REFERENCES user_learning_item(id) ON DELETE CASCADE,
    created_at timestamp with time zone NOT NULL,
    PRIMARY KEY (wordbook_id, learning_item_id)
);

CREATE INDEX wordbook_user_created_at ON wordbook(user_id, created_at, id);
CREATE INDEX learning_item_user_updated_at ON user_learning_item(user_id, updated_at, id);
CREATE INDEX wordbook_learning_item_item ON wordbook_learning_item(learning_item_id, wordbook_id);

COMMENT ON TABLE wordbook IS '用户自己的单词本分类；删除单词本不直接删除仍被其他单词本引用的学习条目';
COMMENT ON COLUMN wordbook.user_id IS '单词本所属账户，注销时级联清除';
COMMENT ON COLUMN wordbook.name IS '同一账户内唯一的单词本名称';
COMMENT ON COLUMN wordbook.description IS '用户可选的单词本说明';
COMMENT ON COLUMN wordbook.version IS 'JPA 乐观锁版本，避免并发编辑静默覆盖';

COMMENT ON TABLE user_learning_item IS '用户对公开或个人词条的独立学习身份；复习进度不写入词典表';
COMMENT ON COLUMN user_learning_item.id IS '学习条目稳定 ID；重新加入同一词条时使用新 ID，隔离旧事件';
COMMENT ON COLUMN user_learning_item.user_id IS '进度所属账户，不使用邮箱或用户名作为外键';
COMMENT ON COLUMN user_learning_item.dictionary_entry_id IS '公开基准词条引用，与个人词条引用二选一';
COMMENT ON COLUMN user_learning_item.personal_custom_entry_id IS '预留个人词条引用，与公开词条引用二选一';
COMMENT ON COLUMN user_learning_item.manual_ear_focus IS '用户手动重点标记；完整重置时一并清除';
COMMENT ON COLUMN user_learning_item.progress_epoch IS '重置代次；旧设备草稿和待提交事件必须携带并校验此值';
COMMENT ON COLUMN user_learning_item.fsrs_algorithm IS '进度使用的调度算法标识';
COMMENT ON COLUMN user_learning_item.fsrs_algorithm_version IS '调度算法版本，尚未复习时为 UNINITIALIZED';
COMMENT ON COLUMN user_learning_item.fsrs_state IS '调度算法私有状态 JSON；当前只建立存储边界，尚未执行 FSRS 计算';
COMMENT ON COLUMN user_learning_item.last_reviewed_at IS '最近一次完整复习的实际答题时间';
COMMENT ON COLUMN user_learning_item.next_review_at IS '调度出的下次复习时间；为空表示尚未完成首次复习';
COMMENT ON COLUMN user_learning_item.review_count IS '完整复习次数，不统计中途尝试';
COMMENT ON COLUMN user_learning_item.lapse_count IS '被 Again 归档的完整复习次数';
COMMENT ON COLUMN user_learning_item.version IS 'JPA 乐观锁版本，供学习状态写入使用';

COMMENT ON TABLE wordbook_learning_item IS '单词本与学习条目的多对多关联；同一学习条目可跨单词本共享一份进度';
COMMENT ON COLUMN wordbook_learning_item.wordbook_id IS '关联的用户单词本';
COMMENT ON COLUMN wordbook_learning_item.learning_item_id IS '关联的共享学习条目';
COMMENT ON COLUMN wordbook_learning_item.created_at IS '加入该单词本的时间';
