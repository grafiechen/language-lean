package com.languagelean.accounts;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import tools.jackson.databind.ObjectMapper;

/** 测试浏览器协议；独立使用标准JCA加密，不调用服务端解密实现。 */
final class PasswordTransportClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    static String sealForm(HttpClient client, URI target, String form) throws Exception {
        Map<String, String> body = new HashMap<>();
        for (var field : form.split("&")) {
            var parts = field.split("=", 2);
            body.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return seal(client, target, JSON.writeValueAsString(body));
    }
    static String seal(HttpClient client, URI target, String plain) throws Exception {
        var issued = issue(client, target);
        return encrypt(issued, target.getPath(), plain);
    }
    static tools.jackson.databind.JsonNode issue(HttpClient client, URI target) throws Exception {
        var csrf = JSON.readTree(client.send(HttpRequest.newBuilder(target.resolve("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        var response = client.send(HttpRequest.newBuilder(target.resolve("/api/v1/auth/password-key"))
                .header("Content-Type", "application/json").header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("path", target.getPath())))).build(), HttpResponse.BodyHandlers.ofString());
        org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode(), response.body());
        org.junit.jupiter.api.Assertions.assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return JSON.readTree(response.body());
    }
    static String encrypt(tools.jackson.databind.JsonNode issued, String path, String plain) throws Exception {
        var iv = new byte[12]; new SecureRandom().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(issued.path("key").asText()), "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("password-v1|" + issued.path("keyId").asText() + "|" + path).getBytes(StandardCharsets.UTF_8));
        return JSON.writeValueAsString(Map.of("keyId", issued.path("keyId").asText(), "nonce", Base64.getEncoder().encodeToString(iv),
                "ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)))));
    }
}
