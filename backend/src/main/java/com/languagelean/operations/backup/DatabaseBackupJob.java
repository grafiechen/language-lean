package com.languagelean.operations.backup;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 任务状态与审计信息，不包含数据库正文和凭据。 */
@Entity @Table(name = "database_backup_job")
class DatabaseBackupJob {
    @Id UUID id;
    @Column(name = "actor_id") UUID actorId;
    @Column(name = "trigger_kind", nullable = false, length = 16) String triggerKind;
    @Column(nullable = false, length = 16) String state;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "started_at") Instant startedAt;
    @Column(name = "finished_at") Instant finishedAt;
    @Column(name = "run_token") UUID runToken;
    @Column(name = "error_code", length = 64) String errorCode;
    protected DatabaseBackupJob() {}
    static DatabaseBackupJob create(UUID id, UUID actor) {
        var row = new DatabaseBackupJob(); row.id = id; row.actorId = actor; row.triggerKind = actor == null ? "SCHEDULED" : "MANUAL";
        row.state = "QUEUED"; row.createdAt = Instant.now(); return row;
    }
}
