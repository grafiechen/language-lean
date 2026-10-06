package com.languagelean.operations.backup;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 云端归档元信息，只保存密钥版本标识，不保存解密密钥。 */
@Entity @Table(name = "database_backup_object")
class DatabaseBackupObject {
    @Id @Column(name = "job_id") UUID jobId;
    @Column(name = "object_key", nullable = false, unique = true, length = 240) String objectKey;
    @Column(name = "storage_scope", nullable = false, length = 64) String storageScope;
    @Column(name = "encryption_key_id", nullable = false, length = 80) String encryptionKeyId;
    @Column(name = "encrypted_bytes", nullable = false) long encryptedBytes;
    @Column(nullable = false, length = 64) String sha256;
    @Column(nullable = false, length = 24) String state;
    @Column(name = "verified_at") Instant verifiedAt;
    @Column(name = "deleted_at") Instant deletedAt;
    protected DatabaseBackupObject() {}
}
