package com.languagelean.operations.backup;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 单行调度锁，与长时间数据库转储分开提交。 */
@Entity @Table(name = "database_backup_control")
class DatabaseBackupControl {
    @Id @Column(length = 16) String id;
    @Column(name = "active_job") UUID activeJob;
    @Column(name = "lease_until") Instant leaseUntil;
    @Column(name = "next_scheduled_at") Instant nextScheduledAt;
    protected DatabaseBackupControl() {}
}
