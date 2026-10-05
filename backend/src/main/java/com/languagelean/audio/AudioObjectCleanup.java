package com.languagelean.audio;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 网络不可用时持久保留云文件删除任务，不保留私人正文。 */
@Entity @Table(name = "audio_object_cleanup")
class AudioObjectCleanup {
    @Id UUID id;
    @Column(name = "object_key", nullable = false, unique = true, length = 240) String objectKey;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    /** JPA恢复入口。 */
    protected AudioObjectCleanup() {}
    /** 对象键稳定去重，重试不会重复排队。 */
    static AudioObjectCleanup of(String key) {
        var row = new AudioObjectCleanup(); row.id = UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        row.objectKey = key; row.createdAt = Instant.now(); return row;
    }
}
