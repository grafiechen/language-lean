package com.languagelean.learning;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** 用户独立私有词条；不与公开词典共用实体或唯一键。 */
@Entity @Table(name = "personal_custom_entry")
class PrivateEntry {
    @Id UUID id;
    @Column(name = "user_id", nullable = false) UUID userId;
    @Column(name = "language_code", nullable = false, length = 16) String languageCode;
    @Column(name = "script_code", nullable = false, length = 4) String scriptCode;
    @Column(nullable = false, length = 200) String written;
    @Column(name = "normalized_written_key", nullable = false, length = 200) String normalizedWrittenKey;
    @Column(name = "content_json", nullable = false, columnDefinition = "text") String contentJson;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Version long version;
    /** JPA恢复入口。 */
    protected PrivateEntry() {}
}
