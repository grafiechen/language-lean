package com.languagelean.dictionary;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 只追加的发布快照；不提供修改和删除已发布内容的方法。 */
@Entity
@Table(name = "dictionary_revision")
class DictionaryRevision {
    @Id UUID id;
    @Column(name = "entry_id", nullable = false) UUID entryId;
    @Column(name = "revision_number", nullable = false) int revisionNumber;
    @Column(nullable = false, columnDefinition = "text") String content;
    @Column(name = "published_by", nullable = false) UUID publishedBy;
    @Column(name = "published_at", nullable = false) Instant publishedAt;
    @Column(nullable = false, length = 500) String note;
    @Column(name = "contribution_id") UUID contributionId;
    @Column(name = "contributed_by") UUID contributedBy;
    @Column(name = "contributor_deleted", nullable = false) boolean contributorDeleted;
    @Column(name = "contribution_source", length = 200) String contributionSource;
    @Column(name = "contribution_license", length = 200) String contributionLicense;

    /** 仅供 JPA 创建历史实体。 */
    protected DictionaryRevision() {}

    /** 从待发布草稿复制完整快照，不保留对可变内容的引用。 */
    static DictionaryRevision publish(DictionaryEntry entry, UUID actor, String note) {
        var revision = new DictionaryRevision();
        revision.id = UUID.randomUUID();
        revision.entryId = entry.id;
        revision.revisionNumber = entry.currentRevision + 1;
        revision.content = entry.draftContent;
        revision.publishedBy = actor;
        revision.publishedAt = Instant.now();
        revision.note = note;
        return revision;
    }
}
