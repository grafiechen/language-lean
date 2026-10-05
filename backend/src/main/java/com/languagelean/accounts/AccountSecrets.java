package com.languagelean.accounts;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/** 随机密码、重置令牌和不可逆摘要；不依赖可预测时间戳或账号信息。 */
final class AccountSecrets {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789", SPECIAL = "!@#$%*-_+";
    private AccountSecrets() {}
    /** 系统生成 16 位初始密码，保证字母、数字和特殊字符，并打乱位置。 */
    static String initialPassword() {
        char[] result = new char[16];
        result[0] = pick(LETTERS); result[1] = pick(DIGITS); result[2] = pick(SPECIAL);
        for (int i = 3; i < result.length; i++) result[i] = pick(LETTERS + DIGITS + SPECIAL);
        for (int i = result.length - 1; i > 0; i--) { int j = RANDOM.nextInt(i + 1); char t = result[i]; result[i] = result[j]; result[j] = t; }
        return new String(result);
    }
    private static char pick(String value) { return value.charAt(RANDOM.nextInt(value.length())); }
    /** 256 位随机令牌，通过 URL fragment 交给页面，不进入服务器访问日志。 */
    static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
