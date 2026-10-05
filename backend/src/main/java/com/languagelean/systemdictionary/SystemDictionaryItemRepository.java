package com.languagelean.systemdictionary;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 系统字典选项的 JPA 查询入口。 */
interface SystemDictionaryItemRepository extends JpaRepository<SystemDictionaryItemEntity, UUID> {
    List<SystemDictionaryItemEntity> findByDictionaryCodeOrderBySortOrderAscDisplayNameAsc(String dictionaryCode);
    boolean existsByDictionaryCodeAndValue(String dictionaryCode, String value);
}
