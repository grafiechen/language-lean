package com.languagelean.accounts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** JCA AES-256-GCM；主密钥来自服务器配置，不能放到数据库、浏览器或仓库。 */
@Component
class PasswordKeyCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec master;
    PasswordKeyCipher(@Value("${app.password-transport.master-key:}") String configured,
                      @Value("${server.servlet.session.cookie.secure:false}") boolean productionHttps) {
        if (configured.isBlank() && productionHttps)
            throw new IllegalStateException("HTTPS 部署必须配置 PASSWORD_TRANSPORT_MASTER_KEY（Base64 编码的随机32字节密钥）");
        byte[] key;
        try { key = configured.isBlank() ? random(32) : Base64.getDecoder().decode(configured); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("PASSWORD_TRANSPORT_MASTER_KEY 格式错误"); }
        if (key.length != 32) throw new IllegalStateException("PASSWORD_TRANSPORT_MASTER_KEY 必须解码为32字节");
        master = new SecretKeySpec(key, "AES"); Arrays.fill(key, (byte) 0);
        // 本地未配置时只保留进程级主密钥；重启后旧的两分钟密钥失效，重新取钥即可。
    }
    static byte[] random(int length) { var bytes = new byte[length]; RANDOM.nextBytes(bytes); return bytes; }
    String wrap(byte[] key, String aad) {
        var nonce = random(12);
        var encrypted = crypt(Cipher.ENCRYPT_MODE, master, nonce, key, aad);
        var packed = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packed, 0, nonce.length);
        System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
        return Base64.getEncoder().encodeToString(packed);
    }
    byte[] unwrap(String wrapped, String aad) {
        var packed = Base64.getDecoder().decode(wrapped);
        if (packed.length != 60) throw new IllegalArgumentException("Invalid wrapped key");
        return crypt(Cipher.DECRYPT_MODE, master, Arrays.copyOf(packed, 12), Arrays.copyOfRange(packed, 12, packed.length), aad);
    }
    /** 标准128位认证标签；密文被篡改、用途或密钥标识不一致均不能解密。 */
    static byte[] crypt(int mode, SecretKeySpec key, byte[] nonce, byte[] input, String aad) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) { throw new IllegalArgumentException("Invalid encrypted password request"); }
    }
}
