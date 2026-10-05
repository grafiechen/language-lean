package com.languagelean.dictionary;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 词典文件的服务端暂存批次；预览和正式发布之间不会重新解释原文件。 */
@Entity
@Table(name = "dictionary_import_batch")
class DictionaryImportBatch {
    @Id UUID id;
    @Column(nullable = false, length = 24) String status;
    @Column(name = "original_file_name", nullable = false, length = 255) String originalFileName;
    @Column(name = "original_sha256", nullable = false, length = 64) String originalSha256;
    @Column(name = "source_name", nullable = false, length = 200) String sourceName;
    @Column(name = "source_version", nullable = false, length = 100) String sourceVersion;
    @Column(name = "license_text", nullable = false, length = 200) String licenseText;
    @Column(name = "total_count", nullable = false) int totalCount;
    @Column(name = "ready_count", nullable = false) int readyCount;
    @Column(name = "duplicate_count", nullable = false) int duplicateCount;
    @Column(name = "invalid_count", nullable = false) int invalidCount;
    @Column(name = "imported_count", nullable = false) int importedCount;
    @Column(name = "apply_duplicate_count", nullable = false) int applyDuplicateCount;
    @Column(name = "normalized_payload", nullable = false, columnDefinition = "text") String normalizedPayload;
    @Column(name = "validation_report", nullable = false, columnDefinition = "text") String validationReport;
    @Column(name = "created_by", nullable = false) UUID createdBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "applied_at") Instant appliedAt;
    @Version long version;

    /** 仅供 JPA 恢复暂存记录。 */
    protected DictionaryImportBatch() {}

    /** 保存一次不可变的校验结果；正式发布只读取这里的规范化内容。 */
    static DictionaryImportBatch validated(String fileName, String sha256, DictionaryImportService.Source source,
                                            int total, int ready, int duplicate, int invalid,
                                            String payload, String report, UUID actor) {
        var batch = new DictionaryImportBatch();
        batch.id = UUID.randomUUID();
        batch.status = "VALIDATED";
        batch.originalFileName = fileName;
        batch.originalSha256 = sha256;
        batch.sourceName = source.name();
        batch.sourceVersion = source.version();
        batch.licenseText = source.license();
        batch.totalCount = total;
        batch.readyCount = ready;
        batch.duplicateCount = duplicate;
        batch.invalidCount = invalid;
        batch.normalizedPayload = payload;
        batch.validationReport = report;
        batch.createdBy = actor;
        batch.createdAt = Instant.now();
        return batch;
    }

    /** 确认发布只允许执行一次，并记录真正插入数量和确认时间。 */
    void applied(int imported, int duplicatesFoundAtApply) {
        status = "APPLIED";
        importedCount = imported;
        applyDuplicateCount = duplicatesFoundAtApply;
        appliedAt = Instant.now();
    }
}
