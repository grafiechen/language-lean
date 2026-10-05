package com.languagelean.accounts;

import java.net.URI;
import jakarta.mail.internet.InternetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SMTP 和公开站点地址通过环境配置提供，管理 API 不返回这些凭据。 */
@Component
@ConfigurationProperties(prefix = "app.mail")
class AccountMailConfiguration {
    private boolean enabled, starttls = true, ssl;
    private String host = "", username = "", password = "", from = "", publicUrl = "";
    private int port = 587;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isStarttls() { return starttls; }
    public void setStarttls(boolean v) { starttls = v; }
    public boolean isSsl() { return ssl; }
    public void setSsl(boolean v) { ssl = v; }
    public String getHost() { return host; }
    public void setHost(String v) { host = v; }
    public int getPort() { return port; }
    public void setPort(int v) { port = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; }
    public String getFrom() { return from; }
    public void setFrom(String v) { from = v; }
    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String v) { publicUrl = v; }

    /** 接收严格的单个邮箱，禁止显示名称、控制字符或多收件人。 */
    static boolean validEmail(String value) {
        if (value == null || value.isBlank() || value.length() > 320 || value.chars().anyMatch(Character::isISOControl)) return false;
        try {
            var address = new InternetAddress(value, true);
            address.validate();
            return !address.isGroup() && address.getPersonal() == null && value.equals(address.getAddress()) && value.contains("@");
        } catch (jakarta.mail.internet.AddressException e) { return false; }
    }

    /** 不从请求 Host 推断地址，防止攻击者把重置邮件导向其他站点。 */
    boolean recoveryConfigured() {
        try {
            var uri = URI.create(publicUrl);
            boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && ("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())));
        } catch (IllegalArgumentException e) { return false; }
    }
}
