package com.languagelean.dictionary;

import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

/** 导入批次仓储；只提供最近批次，避免后台首页一次读取全部历史。 */
interface DictionaryImportBatchRepository extends JpaRepository<DictionaryImportBatch, UUID> {
    List<DictionaryImportBatch> findTop20ByOrderByCreatedAtDesc();
    /** 同一个批次的确认操作串行化，防止双击产生两次发布流程。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from DictionaryImportBatch b where b.id = :id")
    java.util.Optional<DictionaryImportBatch> lockById(UUID id);
}
