package com.languagelean.learning;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 单词本关联仓储；查询显式按单词本或学习条目范围限定。 */
interface WordbookLearningItemRepository extends JpaRepository<WordbookLearningItem, WordbookLearningItemId> {
    @Query("select link from WordbookLearningItem link where link.id.wordbookId = :wordbookId "
            + "order by link.createdAt asc, link.id.learningItemId asc")
    List<WordbookLearningItem> findByWordbook(@Param("wordbookId") UUID wordbookId);

    long countByIdLearningItemId(UUID learningItemId);

    long countByIdWordbookId(UUID wordbookId);

    boolean existsByIdWordbookIdAndIdLearningItemId(UUID wordbookId, UUID learningItemId);
}
