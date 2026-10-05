package com.languagelean.dictionary;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 不随私人编辑更新的投稿快照，只有审核状态及意见可变。 */
@Entity @Table(name = "dictionary_contribution_submission")
class ContributionSubmission {
    @Id UUID id;
    @Column(name="submitted_by",nullable=false) UUID submittedBy;
    @Column(name="private_entry_id") UUID privateEntryId;
    @Column(name="learning_item_id") UUID learningItemId;
    @Column(name="source_id",nullable=false) UUID sourceId;
    @Column(name="source_kind",nullable=false,length=16) String sourceKind;
    @Column(name="source_version",nullable=false) long sourceVersion;
    @Column(name="target_entry_id") UUID targetEntryId;
    @Column(name="base_revision") Integer baseRevision;
    @Column(nullable=false,length=16) String kind;
    @Column(name="language_code",nullable=false,length=16) String languageCode;
    @Column(name="script_code",nullable=false,length=4) String scriptCode;
    @Column(nullable=false,length=200) String written;
    @Column(name="normalized_written_key",nullable=false,length=200) String normalizedWrittenKey;
    @Column(name="content_json",nullable=false,columnDefinition="text") String contentJson;
    @Column(name="base_content_json",columnDefinition="text") String baseContentJson;
    @Column(name="request_hash",nullable=false,length=64) String requestHash;
    @Column(nullable=false,length=24) String status;
    @Column(name="publish_requested",nullable=false) boolean publishRequested;
    @Column(name="submit_note",nullable=false,length=500) String submitNote;
    @Column(name="review_note",nullable=false,length=500) String reviewNote;
    @Column(name="reviewed_by") UUID reviewedBy;
    @Column(name="published_entry_id") UUID publishedEntryId;
    @Column(name="published_revision") Integer publishedRevision;
    @Column(name="active_source_key",length=120) String activeSourceKey;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="reviewed_at") Instant reviewedAt;
    @Version long version;
    /** JPA恢复入口。 */
    protected ContributionSubmission() {}
}
