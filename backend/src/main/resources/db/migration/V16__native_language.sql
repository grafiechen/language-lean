-- 母语是账户偏好，不改变词条语言、发音、学习身份或FSRS进度。
ALTER TABLE user_account ADD COLUMN native_language varchar(35) NOT NULL DEFAULT 'zh-Hans';
COMMENT ON COLUMN user_account.native_language IS '释义和例句译文的首选语言（BCP 47），默认简体中文；发音仍使用词条的学习语言';
