package com.languagelean.accounts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 第一版单实例发送限流；有界缓存只保存 IP/标识摘要，不保存明文邮箱。 */
@Component
class PasswordRecoveryRateLimiter {
    private final Map<String, Window> windows = new LinkedHashMap<>();
    /** 每个 IP 每 10 分钟最多 10 次、同登录标识一分钟一次，未知账号也受限制。 */
    synchronized boolean allow(String ip, String identifier) {
        long now = Instant.now().getEpochSecond();
        windows.entrySet().removeIf(e -> e.getValue().until() <= now);
        var ipKey = "ip:" + AccountSecrets.digest(ip);
        var accountKey = "id:" + AccountSecrets.digest(identifier);
        var ipWindow = windows.get(ipKey);
        if (windows.containsKey(accountKey) || (ipWindow != null && ipWindow.count() >= 10)) return false;
        if (windows.size() >= 4094) return false;
        windows.put(accountKey, new Window(now + 60, 1));
        windows.put(ipKey, ipWindow == null ? new Window(now + 600, 1) : new Window(ipWindow.until(), ipWindow.count() + 1));
        return true;
    }
    private record Window(long until, int count) {}
}
