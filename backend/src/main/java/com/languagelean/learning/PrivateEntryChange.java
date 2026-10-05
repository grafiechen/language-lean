package com.languagelean.learning;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 私有内容修改审计元信息，删除后不留正文或历史副本。 */
@Entity @Table(name = "private_entry_change")
class PrivateEntryChange {
    @Id UUID id;
    @Column(name = "entry_id", nullable = false) UUID entryId;
    @Column(name = "edit_version", nullable = false) long editVersion;
    @Column(name = "changed_at", nullable = false) Instant changedAt;
    /** JPA恢复入口。 */
    protected PrivateEntryChange() {}
    /** 与成功保存处于同一事务。 */
    static PrivateEntryChange saved(PrivateEntry row) {
        var result = new PrivateEntryChange(); result.id = UUID.randomUUID(); result.entryId = row.id;
        result.editVersion = row.version; result.changedAt = row.updatedAt; return result;
    }
}
