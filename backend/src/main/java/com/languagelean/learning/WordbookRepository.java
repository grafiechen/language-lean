package com.languagelean.learning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 单词本的 JPA 访问入口，所有查询都带账户范围。 */
interface WordbookRepository extends JpaRepository<Wordbook, UUID> {
    List<Wordbook> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);
    Optional<Wordbook> findByIdAndUserId(UUID id, UUID userId);
    /** 同一本CSV的确认操作串行化，归属在持锁查询时校验。 */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from Wordbook b where b.id = :id and b.userId = :userId")
    Optional<Wordbook> lockOwned(UUID id, UUID userId);
    boolean existsByUserIdAndName(UUID userId, String name);
}
