package com.languagelean.learning;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 不可变的词条复习事件及调度快照；历史与当前进度分开保存。 */
@Entity
@Table(name = "learning_review_event")
class LearningReviewEvent {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(name = "learning_item_id", nullable = false) UUID learningItemId;
    @Column(name = "attempt_id", nullable = false) UUID attemptId;
    @Column(name = "progress_epoch", nullable = false) UUID progressEpoch;
    @Column(name = "base_version", nullable = false, length = 64) String baseVersion;
    @Column(name = "base_event_id") UUID baseEventId;
    @Column(name = "completed_at", nullable = false) Instant completedAt;
    @Column(name = "received_at", nullable = false) Instant receivedAt;
    @Column(name = "final_rating", nullable = false, length = 16) String finalRating;
    @Column(name = "submission_json", nullable = false, columnDefinition = "text") String submissionJson;
    @Column(name = "state_before", nullable = false, columnDefinition = "text") String stateBefore;
    @Column(name = "state_after", nullable = false, columnDefinition = "text") String stateAfter;
    @Column(name = "scheduler_configuration", nullable = false, columnDefinition = "text") String schedulerConfiguration;
    @Column(name = "algorithm_version", nullable = false, length = 32) String algorithmVersion;
    @Column(name = "next_review_at", nullable = false) Instant nextReviewAt;
    /** 仅供 JPA 从历史表恢复数据。 */
    protected LearningReviewEvent() {}
}
