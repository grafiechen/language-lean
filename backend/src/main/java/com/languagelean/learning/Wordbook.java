package com.languagelean.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** 用户单词本聚合根；单词本只负责分类，不复制学习进度。 */
@Entity
@Table(name = "wordbook")
class Wordbook {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(nullable = false, length = 100) String name;
    @Column(nullable = false, length = 500) String description;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Version long version;

    /** 仅供 JPA 恢复持久化单词本。 */
    protected Wordbook() {}

    /** 创建属于当前账户的单词本；名称唯一性由服务校验并由数据库兜底。 */
    static Wordbook create(UUID userId, String name, String description) {
        var wordbook = new Wordbook();
        wordbook.id = UUID.randomUUID();
        wordbook.userId = userId;
        wordbook.name = name;
        wordbook.description = description;
        wordbook.createdAt = Instant.now();
        wordbook.updatedAt = wordbook.createdAt;
        return wordbook;
    }
}
