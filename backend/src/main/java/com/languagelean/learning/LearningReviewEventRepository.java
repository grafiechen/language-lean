package com.languagelean.learning;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 复习事件仓储；所有基准和历史查询均限定在当前学习身份内。 */
interface LearningReviewEventRepository extends JpaRepository<LearningReviewEvent, UUID> {
    Optional<LearningReviewEvent> findByUserIdAndAttemptId(UUID userId, UUID attemptId);
    List<LearningReviewEvent> findByLearningItemIdAndCompletedAt(UUID learningItemId, Instant completedAt);
    List<LearningReviewEvent> findTop50ByLearningItemIdOrderByCompletedAtDescIdDesc(UUID learningItemId);
    void deleteByLearningItemId(UUID learningItemId);
    /** 导出全部历史，不沿用页面最近50次的展示限制。 */
    List<LearningReviewEvent> findByUserIdOrderByCompletedAtAscIdAsc(UUID userId);
}
