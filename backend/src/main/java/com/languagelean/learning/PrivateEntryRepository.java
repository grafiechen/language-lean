package com.languagelean.learning;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
/** 私有词条仓储，所有外部操作均附账户条件。 */
interface PrivateEntryRepository extends JpaRepository<PrivateEntry, UUID> {
    /** 注销先锁源词条，避免新增音频身份晚于清理对象键的查询。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PrivateEntry e where e.userId = :userId order by e.id")
    List<PrivateEntry> lockAccountEntries(UUID userId);
    Optional<PrivateEntry> findByIdAndUserId(UUID id, UUID userId);
    /** CSV导入只按本人唯一写法复用私人身份，不读取其他用户的内容。 */
    Optional<PrivateEntry> findByUserIdAndLanguageCodeAndScriptCodeAndNormalizedWrittenKey(UUID userId, String language, String script, String key);
    /** 导出未加入单词本的私有词条时仍强制限定所属账户。 */
    List<PrivateEntry> findByUserIdOrderByIdAsc(UUID userId);
    boolean existsByUserIdAndLanguageCodeAndScriptCodeAndNormalizedWrittenKey(UUID userId, String languageCode, String scriptCode, String normalizedWrittenKey);
    /** 不将私人正文写入搜索条件，分页查询用户自己的写法。 */
    @Query("select e from PrivateEntry e where e.userId = :userId and locate(:query, e.normalizedWrittenKey) > 0")
    Page<PrivateEntry> search(UUID userId, String query, Pageable page);
    /** 与编辑、删除串行化，版本检查在锁内完成。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PrivateEntry e where e.id = :id and e.userId = :userId")
    Optional<PrivateEntry> lockOwned(UUID id, UUID userId);
}
