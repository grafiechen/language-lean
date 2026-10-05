package com.languagelean.accounts;

import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 邮件找回：只向账户原邮箱发送链接，数据库仅保存摘要，有效 30 分钟、一次性。 */
@Service
class PasswordRecoveryService {
    private final UserAccountRepository accounts;
    private final UserLoginIdentifierRepository identifiers;
    private final AccountPasswordResetRepository resets;
    private final AccountMailPort mail;
    private final AccountMailConfiguration config;
    private final PasswordEncoder encoder;
    PasswordRecoveryService(UserAccountRepository accounts, UserLoginIdentifierRepository identifiers,
                            AccountPasswordResetRepository resets, AccountMailPort mail, AccountMailConfiguration config, PasswordEncoder encoder) {
        this.accounts = accounts; this.identifiers = identifiers; this.resets = resets;
        this.mail = mail; this.config = config; this.encoder = encoder;
    }
    /** 不返回账号存在与否；同账号的并发发送通过账户锁和数据库时间窗口限制。 */
    @Transactional
    void request(String identifier) {
        if (!mail.configured() || !config.recoveryConfigured()) return;
        var id = identifiers.findAccountIdByNormalizedIdentifier(identifier);
        if (id.isEmpty()) return;
        var account = accounts.lockById(id.get()).orElse(null);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) return;
        var now = Instant.now();
        if (resets.findFirstByAccountIdOrderByCreatedAtDesc(account.getId())
                .filter(r -> r.getCreatedAt().plusSeconds(60).isAfter(now)).isPresent()) return;
        String token = AccountSecrets.token();
        resets.deleteByAccountId(account.getId()); resets.flush();
        resets.saveAndFlush(new AccountPasswordReset(token, account, now));
        String base = config.getPublicUrl().replaceAll("/+$", "");
        mail.sendPasswordReset(account.getEmail(), account.getUsername(), base + "/reset-password#token=" + token);
    }
    /** 锁账号后再次读取凭据，两个同时提交的请求只有一个能够成功消费。 */
    @Transactional
    void complete(String token, String password, String confirmation) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidLink();
        if (!PasswordPolicy.isValid(password)) throw new ResponseStatusException(BAD_REQUEST, PasswordPolicy.MESSAGE);
        if (!password.equals(confirmation)) throw new ResponseStatusException(BAD_REQUEST, "两次输入的新密码不一致");
        String hash = AccountSecrets.digest(token);
        var snapshot = resets.findById(hash).orElseThrow(PasswordRecoveryService::invalidLink);
        var account = accounts.lockById(snapshot.getAccountId()).orElseThrow(PasswordRecoveryService::invalidLink);
        // findById 已读的实体会留在持久化上下文；existsById 回查数据库防止并发删除后重复使用。
        if (!resets.existsById(hash) || account.getStatus() != AccountStatus.ACTIVE || !snapshot.valid(account, Instant.now())) throw invalidLink();
        account.changePassword(encoder.encode(password));
        resets.deleteByAccountId(account.getId()); resets.flush();
    }
    private static ResponseStatusException invalidLink() { return new ResponseStatusException(BAD_REQUEST, "重置链接无效或已过期，请重新申请"); }
}
