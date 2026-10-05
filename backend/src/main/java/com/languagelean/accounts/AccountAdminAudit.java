package com.languagelean.accounts;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 不含密码、邮件正文和学习内容的账号管理审计。 */
@Entity
@Table(name = "account_admin_audit")
class AccountAdminAudit {
    @Id private UUID id;
    @Column(name = "actor_id", nullable = false) private UUID actorId;
    @Column(name = "target_id", nullable = false) private UUID targetId;
    @Column(nullable = false, length = 24) private String action;
    @Column(name = "performed_at", nullable = false) private Instant performedAt;
    protected AccountAdminAudit() {}
    /** 与账号操作在同一事务中记录。 */
    AccountAdminAudit(UUID actor, UUID target, String action) {
        id = UUID.randomUUID(); actorId = actor; targetId = target;
        this.action = action; performedAt = Instant.now();
    }
}
