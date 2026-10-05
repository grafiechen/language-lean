package com.languagelean.accounts;

import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 后台分配一次性密钥；POST 要求 CSRF，响应严禁缓存。 */
@RestController @RequestMapping("/api/v1/auth/password-key")
class PasswordTransportController {
    private final PasswordKeyTransactions transactions;
    private final Map<String, Window> limits = new LinkedHashMap<>();
    PasswordTransportController(PasswordKeyTransactions transactions) { this.transactions = transactions; }
    @PostMapping
    ResponseEntity<Issued> issue(@RequestBody Purpose body, HttpServletRequest request) {
        if (body == null || body.path() == null || !protectedPath(body.path())) throw new ResponseStatusException(BAD_REQUEST, "无效的密码接口");
        if (!allow(request.getRemoteAddr())) throw new ResponseStatusException(TOO_MANY_REQUESTS, "密码密钥申请过于频繁，请稍后再试");
        var session = request.getSession(true);
        synchronized (session) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transactions.issue(AccountSecrets.digest(session.getId()), body.path()));
        }
    }
    /** 地址取自连接，不能信任任意客户端填写的 X-Forwarded-For；限流缓存有界。 */
    private synchronized boolean allow(String address) {
        var now = Instant.now(); limits.entrySet().removeIf(e -> !now.isBefore(e.getValue().until()));
        var id = AccountSecrets.digest(address); var old = limits.get(id);
        if (old == null && limits.size() >= 4096 || old != null && old.count() >= 120) return false;
        limits.put(id, old == null ? new Window(now.plusSeconds(60), 1) : new Window(old.until(), old.count() + 1));
        return true;
    }
    /** 明确登记每一个携带密码的接口，未知路径不能申请密码传输密钥。 */
    public static boolean protectedPath(String path) {
        return java.util.Set.of("/api/v1/auth/login", "/api/v1/auth/password", "/api/v1/auth/password-reset", "/api/v1/auth/close-account").contains(path)
                || path.matches("/api/v1/admin/accounts/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/delete");
    }
    record Purpose(String path) {}
    record Issued(UUID keyId, String algorithm, String key, Instant expiresAt) {}
    private record Window(Instant until, int count) {}
}
