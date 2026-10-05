package com.languagelean.learning;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 个人内容修改审计，只记录身份、版本和时间，不复制私人正文。 */
@Entity
@Table(name = "personal_entry_override_change")
class PersonalEntryOverrideChange {
    @Id UUID id;
    @Column(name = "learning_item_id", nullable = false) UUID learningItemId;
    @Column(name = "content_revision", nullable = false) long contentRevision;
    @Column(name = "changed_at", nullable = false) Instant changedAt;

    /** JPA 恢复入口。 */
    protected PersonalEntryOverrideChange() {}
    /** 审计与个人内容在同一个事务中保存，失败时一并回滚。 */
    static PersonalEntryOverrideChange saved(PersonalEntryOverride row) {
        var change = new PersonalEntryOverrideChange(); change.id = UUID.randomUUID();
        change.learningItemId = row.learningItemId; change.contentRevision = row.contentRevision;
        change.changedAt = row.updatedAt; return change;
    }
}
