package com.languagelean.audio;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 上传成功后的独立音频快照；文件保存在对象存储。 */
@Entity @Table(name = "audio_version")
class AudioVersion {
    @Id UUID id;
    @Column(name = "audio_asset_id", nullable = false) UUID audioAssetId;
    @Column(name = "version_number", nullable = false) int versionNumber;
    @Column(nullable = false, length = 32) String provider;
    @Column(nullable = false, length = 32) String model;
    @Column(nullable = false, length = 120) String voice;
    @Column(name = "pronunciation_locale", nullable = false, length = 35) String pronunciationLocale;
    @Column(name = "text_hash", nullable = false, length = 64) String textHash;
    @Column(name = "generation_fingerprint", nullable = false, length = 64) String generationFingerprint;
    @Column(name = "file_hash", nullable = false, length = 64) String fileHash;
    @Column(name = "object_key", nullable = false, length = 240) String objectKey;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    /** 供 JPA 恢复快照。 */
    protected AudioVersion() {}
}
