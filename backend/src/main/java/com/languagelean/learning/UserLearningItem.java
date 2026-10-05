package com.languagelean.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** 用户对一个词条的独立学习身份；跨单词本共享此实体的进度。 */
@Entity
@Table(name = "user_learning_item")
class UserLearningItem {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(name = "dictionary_entry_id") UUID dictionaryEntryId;
    @Column(name = "personal_custom_entry_id") UUID personalCustomEntryId;
    @Column(name = "manual_ear_focus", nullable = false) boolean manualEarFocus;
    @Column(name = "automatic_ear_focus", nullable = false) boolean automaticEarFocus;
    @Column(name = "last_review_event_id") UUID lastReviewEventId;
    @Column(name = "progress_epoch", nullable = false) UUID progressEpoch;
    @Column(name = "fsrs_algorithm", nullable = false, length = 32) String fsrsAlgorithm;
    @Column(name = "fsrs_algorithm_version", nullable = false, length = 32) String fsrsAlgorithmVersion;
    @Column(name = "fsrs_state", nullable = false, columnDefinition = "text") String fsrsState;
    @Column(name = "last_reviewed_at") Instant lastReviewedAt;
    @Column(name = "next_review_at") Instant nextReviewAt;
    @Column(name = "review_count", nullable = false) int reviewCount;
    @Column(name = "lapse_count", nullable = false) int lapseCount;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Version long version;

    /** 仅供 JPA 恢复持久化学习条目。 */
    protected UserLearningItem() {}

    /** 从公开基准词条创建初始学习条目；个人词条接入时复用同一状态模型。 */
    static UserLearningItem forDictionary(UUID userId, UUID dictionaryEntryId) {
        var item = new UserLearningItem();
        item.id = UUID.randomUUID();
        item.userId = userId;
        item.dictionaryEntryId = dictionaryEntryId;
        item.progressEpoch = UUID.randomUUID();
        item.fsrsAlgorithm = "FSRS";
        item.fsrsAlgorithmVersion = "UNINITIALIZED";
        item.fsrsState = "{}";
        item.createdAt = Instant.now();
        item.updatedAt = item.createdAt;
        return item;
    }

    /** 独立私有词条复用相同进度模型，不占用公开词条引用。 */
    static UserLearningItem forPrivate(UUID userId, UUID privateEntryId) {
        var item = forDictionary(userId, null); item.personalCustomEntryId = privateEntryId; return item;
    }
    /** 完整重置共享学习进度；内容和单词本关联由服务保留。 */
    void resetProgress() {
        manualEarFocus = false;
        automaticEarFocus = false;
        lastReviewEventId = null;
        progressEpoch = UUID.randomUUID();
        fsrsAlgorithmVersion = "UNINITIALIZED";
        fsrsState = "{}";
        lastReviewedAt = null;
        nextReviewAt = null;
        reviewCount = 0;
        lapseCount = 0;
        updatedAt = Instant.now();
    }
}
