package com.languagelean.accounts;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 短期密码传输密钥；数据库仅保存会话摘要和经过主密钥加密的密钥。 */
@Entity @Table(name = "password_transport_key")
class PasswordTransportKey {
    @Id UUID id;
    @Column(name = "session_hash", nullable = false, length = 64) String sessionHash;
    @Column(nullable = false, length = 120) String purpose;
    @Column(name = "wrapped_key", nullable = false, length = 128) String wrappedKey;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    protected PasswordTransportKey() {}
    PasswordTransportKey(UUID id, String sessionHash, String purpose, String wrappedKey, Instant expiresAt) {
        this.id = id; this.sessionHash = sessionHash; this.purpose = purpose;
        this.wrappedKey = wrappedKey; this.expiresAt = expiresAt;
    }
    String associatedData() { return "password-key-v1|" + id + "|" + purpose + "|" + sessionHash; }
}
