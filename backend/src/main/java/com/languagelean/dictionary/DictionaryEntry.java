package com.languagelean.dictionary;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 基准词条聚合根；只有草稿可变，已发布快照由独立版本实体保存。 */
@Entity
@Table(name = "dictionary_entry")
class DictionaryEntry {
    @Id UUID id;
    @Column(name = "language_code", nullable = false, length = 16) String languageCode;
    @Column(name = "script_code", nullable = false, length = 4) String scriptCode;
    @Column(nullable = false, length = 200) String written;
    @Column(name = "normalized_written_key", nullable = false, length = 200) String normalizedWrittenKey;
    @Column(nullable = false, length = 16) String status;
    @Column(name = "current_revision", nullable = false) int currentRevision;
    @Column(name = "draft_content", columnDefinition = "text") String draftContent;
    @Column(name = "draft_base_revision") Integer draftBaseRevision;
    @Column(name = "created_by", nullable = false) UUID createdBy;
    @Column(name = "updated_by", nullable = false) UUID updatedBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Version long version;

    /** 仅供 JPA 实例化；业务创建通过工厂补齐身份和审计信息。 */
    protected DictionaryEntry() {}

    /** 创建尚未公开的词条；不可变身份与后续内容版本分开。 */
    static DictionaryEntry create(String language, String script, String written, String key,
                                  String content, UUID actor) {
        var entry = new DictionaryEntry();
        entry.id = UUID.randomUUID();
        entry.languageCode = language;
        entry.scriptCode = script;
        entry.written = written;
        entry.normalizedWrittenKey = key;
        entry.status = "DRAFT";
        entry.createdBy = actor;
        entry.createdAt = Instant.now();
        entry.saveDraft(content, actor);
        return entry;
    }

    /** 保存草稿不改变公开版本，后续发布必须仍基于同一个公开版。 */
    void saveDraft(String content, UUID actor) {
        draftContent = content;
        draftBaseRevision = currentRevision;
        touch(actor);
    }

    /** 审计信息同时使只读公开内容以外的状态操作参与乐观锁更新。 */
    void touch(UUID actor) {
        updatedBy = actor;
        updatedAt = Instant.now();
    }
}
