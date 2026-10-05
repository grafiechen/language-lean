package com.languagelean.audio;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 一个读音/例句的权限隔离音频身份及跨进程任务租约。 */
@Entity @Table(name = "audio_asset")
class AudioAsset {
    @Id UUID id;
    @Column(name = "dictionary_entry_id") UUID dictionaryEntryId;
    @Column(name = "personal_custom_entry_id") UUID personalCustomEntryId;
    @Column(name = "learning_item_id") UUID learningItemId;
    @Column(name = "content_scope", nullable = false, length = 16) String contentScope;
    @Column(nullable = false, length = 16) String kind;
    @Column(name = "resource_id", nullable = false) UUID resourceId;
    @Column(name = "current_version_id") UUID currentVersionId;
    @Column(name = "generation_token") UUID generationToken;
    @Column(name = "generation_fingerprint", length = 64) String generationFingerprint;
    @Column(name = "lease_until") Instant leaseUntil;
    @Column(name = "failure_code", length = 64) String failureCode;
    @Version long version;
    /** 供 JPA 恢复身份。 */
    protected AudioAsset() {}
}
