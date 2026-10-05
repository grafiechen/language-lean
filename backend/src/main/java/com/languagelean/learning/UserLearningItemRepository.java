package com.languagelean.learning;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

/** 学习条目仓储；公开词条在同一账户内只能对应一份学习进度。 */
interface UserLearningItemRepository extends JpaRepository<UserLearningItem, UUID> {
    /** 注销时锁住全部本人进度，阻止并发答题和私人音频注册。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from UserLearningItem i where i.userId = :userId order by i.id")
    java.util.List<UserLearningItem> lockAccountItems(UUID userId);
    Optional<UserLearningItem> findByUserIdAndDictionaryEntryId(UUID userId, UUID dictionaryEntryId);
    Optional<UserLearningItem> findByUserIdAndPersonalCustomEntryId(UUID userId, UUID personalCustomEntryId);
    Optional<UserLearningItem> findByIdAndUserId(UUID id, UUID userId);
    /** 完整备份按账号查询，不受单词本分类或分页限制。 */
    java.util.List<UserLearningItem> findByUserIdOrderByIdAsc(UUID userId);
    /** 仅查询当前认证账户提供的学习身份，其他账号的 ID 统一视为不存在。 */
    java.util.List<UserLearningItem> findByUserIdAndIdIn(UUID userId, java.util.Collection<UUID> ids);
    /** 串行化同词的提交、重置和删除；数据库事务内持有行锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from UserLearningItem i where i.id = :id and i.userId = :userId")
    Optional<UserLearningItem> lockByIdAndUserId(UUID id, UUID userId);
}
