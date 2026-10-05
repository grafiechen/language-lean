package com.languagelean.accounts;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 邮件地址、公开 URL、TLS 冲突及防重复发送的配置边界。 */
class AccountConfigurationTest {
    @Test void publicUrlMustBeTrustedHttpsOrLoopbackWithoutInjectedComponents() {
        var config = new AccountMailConfiguration();
        for (var url : new String[]{"https://study.example.com", "http://localhost:5174", "http://127.0.0.1:5174"}) {
            config.setPublicUrl(url); assertTrue(config.recoveryConfigured());
        }
        for (var url : new String[]{"", "http://study.example.com", "https://user:pass@study.example.com", "https://study.example.com?redirect=evil", "https://study.example.com#token=evil", "javascript:alert(1)"}) {
            config.setPublicUrl(url); assertFalse(config.recoveryConfigured());
        }
    }
    @Test void mailCannotBeReadyWhenDisabledOrTlsModesConflict() {
        var config = new AccountMailConfiguration(); var service = new SmtpAccountMailService(config);
        config.setHost("mailpit"); config.setFrom("noreply@example.test"); config.setPort(1025);
        assertFalse(service.configured()); config.setEnabled(true); assertTrue(service.configured());
        config.setSsl(true); assertFalse(service.configured()); config.setStarttls(false); assertTrue(service.configured());
        config.setFrom("a@example.test\r\nBcc: b@example.test"); assertFalse(service.configured());
    }
    @Test void rateLimitsUnknownIdentifiersAndBoundsPerIpRequests() {
        var limiter = new PasswordRecoveryRateLimiter();
        assertTrue(limiter.allow("192.0.2.1", "unknown")); assertFalse(limiter.allow("192.0.2.2", "unknown"));
        for (int i = 0; i < 9; i++) assertTrue(limiter.allow("192.0.2.1", "unknown-" + i));
        assertFalse(limiter.allow("192.0.2.1", "unknown-extra"));
        assertTrue(limiter.allow("192.0.2.2", "unknown-new"));
    }
}
