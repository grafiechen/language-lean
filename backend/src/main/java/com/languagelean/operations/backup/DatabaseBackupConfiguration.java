package com.languagelean.operations.backup;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 备份私有配置只来自服务器；默认关闭，管理接口不能暴露连接和密钥。 */
@Component @ConfigurationProperties(prefix = "app.backup")
public class DatabaseBackupConfiguration {
    private boolean enabled;
    private int intervalHours, retentionDays, timeoutSeconds = 1800;
    private String encryptionKey = "", keyId = "", directory = "/tmp/language-lean-backups";
    private String r2AccountId = "", r2AccessKeyId = "", r2SecretAccessKey = "", r2Bucket = "";
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public int getIntervalHours() { return intervalHours; } public void setIntervalHours(int v) { intervalHours = v; }
    public int getRetentionDays() { return retentionDays; } public void setRetentionDays(int v) { retentionDays = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public String getEncryptionKey() { return encryptionKey; } public void setEncryptionKey(String v) { encryptionKey = v; }
    public String getKeyId() { return keyId; } public void setKeyId(String v) { keyId = v; }
    public String getDirectory() { return directory; } public void setDirectory(String v) { directory = v; }
    public String getR2AccountId() { return r2AccountId; } public void setR2AccountId(String v) { r2AccountId = v; }
    public String getR2AccessKeyId() { return r2AccessKeyId; } public void setR2AccessKeyId(String v) { r2AccessKeyId = v; }
    public String getR2SecretAccessKey() { return r2SecretAccessKey; } public void setR2SecretAccessKey(String v) { r2SecretAccessKey = v; }
    public String getR2Bucket() { return r2Bucket; } public void setR2Bucket(String v) { r2Bucket = v; }
    /** 不把实际密钥带入错误文本；标识用于选取历史解密密钥，不是密钥本身。 */
    public boolean encryptionConfigured() {
        try { return Base64.getDecoder().decode(encryptionKey).length == 32 && keyId.matches("[A-Za-z0-9_-]{1,80}"); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    public boolean policyValid() { return intervalHours >= 0 && intervalHours <= 8760 && retentionDays >= 0 && retentionDays <= 36500 && timeoutSeconds >= 30 && timeoutSeconds <= 3600; }
}
