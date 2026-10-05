package com.languagelean.audio;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 公共音频反馈快照；只保存用户明确提交的说明，不引用私人内容。 */
@Entity @Table(name = "audio_feedback")
class AudioFeedback {
    @Id UUID id;
    @Column(name = "submitted_by", nullable = false) UUID submittedBy;
    @Column(name = "dictionary_entry_id", nullable = false) UUID entryId;
    @Column(name = "resource_id", nullable = false) UUID resourceId;
    @Column(nullable = false, length = 16) String kind;
    @Column(nullable = false, length = 32) String category;
    @Column(name = "reported_revision", nullable = false) int reportedRevision;
    @Column(name = "reported_audio_version_id") UUID reportedAudioVersionId;
    @Column(name = "baseline_audio_version_id") UUID baselineAudioVersionId;
    @Column(name = "pronunciation_text", nullable = false, columnDefinition = "text") String pronunciationText;
    @Column(nullable = false, length = 1000) String description;
    @Column(nullable = false, length = 16) String status = "PENDING";
    @Column(name = "resolution_note", nullable = false, length = 1000) String resolutionNote = "";
    @Column(name = "reviewed_by") UUID reviewedBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "reviewed_at") Instant reviewedAt;
    @Version long version;
    /** JPA 实例化入口；提交用例核对公开资源后填写字段。 */
    protected AudioFeedback() {}
}
