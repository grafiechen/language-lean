package com.languagelean.learning;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 单词本与学习条目的关联；关联删除和学习条目删除由用例服务按最后引用规则处理。 */
@Entity
@Table(name = "wordbook_learning_item")
class WordbookLearningItem {
    @EmbeddedId WordbookLearningItemId id;
    @Column(name = "created_at", nullable = false) Instant createdAt;

    /** 仅供 JPA 恢复关联记录。 */
    protected WordbookLearningItem() {}

    /** 创建一个单词本关联，不复制 UserLearningItem。 */
    static WordbookLearningItem create(UUID wordbookId, UUID learningItemId) {
        var link = new WordbookLearningItem();
        link.id = new WordbookLearningItemId(wordbookId, learningItemId);
        link.createdAt = Instant.now();
        return link;
    }
}
