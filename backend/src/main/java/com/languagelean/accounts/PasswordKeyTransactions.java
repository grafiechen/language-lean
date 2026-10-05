package com.languagelean.accounts;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 消费独立提交：之后解密或登录失败也不能回滚删除、再次使用同一密钥。 */
@Service @Transactional(propagation = Propagation.REQUIRES_NEW)
class PasswordKeyTransactions {
    private final PasswordTransportKeyRepository keys;
    private final PasswordKeyCipher cipher;
    PasswordKeyTransactions(PasswordTransportKeyRepository keys, PasswordKeyCipher cipher) { this.keys = keys; this.cipher = cipher; }
    PasswordTransportController.Issued issue(String sessionHash, String purpose) {
        var now = Instant.now();
        if (keys.countBySessionHashAndExpiresAtAfter(sessionHash, now) >= 8)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "未使用的密码密钥过多，请稍后再试");
        var id = UUID.randomUUID(); var key = PasswordKeyCipher.random(32);
        try {
            var row = new PasswordTransportKey(id, sessionHash, purpose, null, now.plusSeconds(120));
            row.wrappedKey = cipher.wrap(key, row.associatedData());
            keys.saveAndFlush(row);
            return new PasswordTransportController.Issued(id, "AES-256-GCM", Base64.getEncoder().encodeToString(key), row.expiresAt);
        } finally { Arrays.fill(key, (byte) 0); }
    }
    PasswordTransportKey consume(UUID id, String sessionHash, String purpose) {
        var row = keys.lockById(id).orElse(null);
        if (row == null || !row.sessionHash.equals(sessionHash) || !row.purpose.equals(purpose)) return null;
        keys.delete(row); keys.flush();
        return row; // 有效期在事务外校验，过期请求也提交删除。
    }
    /** 定期清除无人使用的过期密文，不保留密钥历史。 */
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void clearExpired() { keys.deleteExpired(Instant.now()); }
}
