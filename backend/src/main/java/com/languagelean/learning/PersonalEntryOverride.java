package com.languagelean.learning;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 私有内容附着于共享学习身份；内容版本与 FSRS 状态完全独立。 */
@Entity
@Table(name = "personal_entry_override")
class PersonalEntryOverride {
    @Id @Column(name = "learning_item_id") UUID learningItemId;
    @Column(name = "meaning_override", length = 4000) String meaningOverride;
    @Column(name = "readings_override_json", columnDefinition = "text") String readingsOverrideJson;
    @Column(name = "senses_override_json", columnDefinition = "text") String sensesOverrideJson;
    @Column(name = "audio_revision", nullable = false) long audioRevision;
    @Column(nullable = false, length = 10000) String notes;
    @Column(name = "tags_json", nullable = false, columnDefinition = "text") String tagsJson;
    @Column(name = "content_revision", nullable = false) long contentRevision;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    /** JPA 恢复入口。 */
    protected PersonalEntryOverride() {}
    /** 初次保存使用版本零作为基准，真实写入后版本从一开始。 */
    static PersonalEntryOverride create(UUID itemId) {
        var row = new PersonalEntryOverride(); row.learningItemId = itemId; return row;
    }
}
