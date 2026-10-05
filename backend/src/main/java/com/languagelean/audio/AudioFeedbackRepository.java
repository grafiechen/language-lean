package com.languagelean.audio;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;

/** 按本人和状态分页查询反馈，处理时使用数据库行锁。 */
interface AudioFeedbackRepository extends JpaRepository<AudioFeedback, UUID> {
    /** 普通用户固定本人条件；后台传空 owner，但由管理员路由先完成授权。 */
    @Query("""
        select f from AudioFeedback f where (:owner is null or f.submittedBy = :owner)
        and (:status = '' or f.status = :status)
        and (:entryId is null or f.entryId = :entryId)
        and (:resourceId is null or f.resourceId = :resourceId)
        and (:kind = '' or f.kind = :kind)
        """)
    Page<AudioFeedback> search(UUID owner, String status, UUID entryId, UUID resourceId, String kind, Pageable pageable);
    /** 串行处理同一反馈，再验证管理员看到的版本。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from AudioFeedback f where f.id = :id")
    Optional<AudioFeedback> lockById(UUID id);
}
