package com.languagelean.accounts;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 公开的邮件找回接口仍受 CSRF 保护；提交响应不暴露账号是否存在。 */
@RestController
@RequestMapping("/api/v1/auth")
class PasswordRecoveryController {
    private final PasswordRecoveryService service;
    private final PasswordRecoveryRateLimiter limiter;
    private final AccountMailPort mail;
    private final AccountMailConfiguration config;
    PasswordRecoveryController(PasswordRecoveryService service, PasswordRecoveryRateLimiter limiter, AccountMailPort mail, AccountMailConfiguration config) {
        this.service = service; this.limiter = limiter; this.mail = mail; this.config = config;
    }
    @GetMapping("/password-recovery") Status status() { return new Status(mail.configured() && config.recoveryConfigured()); }
    /** 无论未知、禁用、限流或发送失败，都返回同一句话，避免泄露账号存在性。 */
    @PostMapping("/password-recovery")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Message request(@RequestBody Request body, HttpServletRequest request) {
        String identifier = body.identifier() == null ? "" : UserAccountEntity.normalize(body.identifier());
        if (!identifier.isBlank() && identifier.length() <= 320 && limiter.allow(request.getRemoteAddr(), identifier)) {
            try { service.request(identifier); }
            catch (ResponseStatusException e) { if (e.getStatusCode().value() != 503) throw e; }
        }
        return new Message("如果账号存在且已启用，我们会向注册邮箱发送重置链接；请稍候检查邮箱，也可以查看垃圾邮件。");
    }
    @PostMapping("/password-reset")
    void complete(@RequestBody Reset body) { service.complete(body.token(), body.password(), body.confirmation()); }
    record Status(boolean configured) {}
    record Request(String identifier) {}
    record Message(String message) {}
    record Reset(String token, String password, String confirmation) {}
}
