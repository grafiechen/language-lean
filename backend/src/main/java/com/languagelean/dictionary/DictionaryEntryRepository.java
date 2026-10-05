package com.languagelean.dictionary;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;

/** 词条查询和写入锁入口；搜索用参数化 JPA 条件，避免直接拼接 SQL。 */
interface DictionaryEntryRepository extends JpaRepository<DictionaryEntry, UUID>, JpaSpecificationExecutor<DictionaryEntry> {
    /** 投稿按唯一写法寻找既有基准身份，不创建重复词条。 */
    Optional<DictionaryEntry> findByLanguageCodeAndScriptCodeAndNormalizedWrittenKey(String language, String script, String key);
    /** 批量导入预检和确认阶段都按基准词典唯一身份判断重复。 */
    boolean existsByLanguageCodeAndScriptCodeAndNormalizedWrittenKey(String languageCode, String scriptCode,
                                                                     String normalizedWrittenKey);
    /** 同一词条的草稿、发布、封禁操作串行化，再校验客户端版本。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DictionaryEntry e where e.id = :id")
    Optional<DictionaryEntry> lockById(UUID id);
}
