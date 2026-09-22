package com.languagelean.dictionary;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** 历史版本按所属词条和序号读取，防止跨词条恢复错误内容。 */
interface DictionaryRevisionRepository extends JpaRepository<DictionaryRevision, UUID> {
    Optional<DictionaryRevision> findByEntryIdAndRevisionNumber(UUID entryId, int revisionNumber);
    Page<DictionaryRevision> findByEntryIdOrderByRevisionNumberDesc(UUID entryId, Pageable page);
}
