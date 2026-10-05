package com.languagelean.accounts;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 邮件重置链接的服务端凭据；原始随机令牌不写入数据库。 */
@Entity
@Table(name = "account_password_reset")
class AccountPasswordReset {
    @Id @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(name = "password_fingerprint", nullable = false, length = 64) private String passwordFingerprint;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    protected AccountPasswordReset() {}
    AccountPasswordReset(String token, UserAccountEntity account, Instant now) {
        tokenHash = AccountSecrets.digest(token); accountId = account.getId();
        passwordFingerprint = fingerprint(account);
        createdAt = now; expiresAt = now.plusSeconds(1800);
    }
    UUID getAccountId() { return accountId; }
    Instant getCreatedAt() { return createdAt; }
    /** 同时校验有效期和签发时凭据，任何改密都会让先前链接失效。 */
    boolean valid(UserAccountEntity account, Instant now) {
        return now.isBefore(expiresAt) && passwordFingerprint.equals(fingerprint(account));
    }
    private static String fingerprint(UserAccountEntity account) { return AccountSecrets.digest(account.getPasswordHash() + ":" + account.getSecurityVersion()); }
}
