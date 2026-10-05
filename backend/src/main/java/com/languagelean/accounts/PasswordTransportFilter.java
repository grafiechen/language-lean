package com.languagelean.accounts;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** CSRF 校验之后、登录过滤器之前解密；网络接口一律拒绝旧的明文密码请求。 */
@Component
public class PasswordTransportFilter extends OncePerRequestFilter {
    private final PasswordKeyTransactions transactions;
    private final PasswordKeyCipher cipher;
    private final ObjectMapper json;
    PasswordTransportFilter(PasswordKeyTransactions transactions, PasswordKeyCipher cipher, ObjectMapper json) {
        this.transactions = transactions; this.cipher = cipher; this.json = json;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PasswordTransportController.protectedPath(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest decrypted;
        try {
            if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new IllegalArgumentException();
            var bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) throw new IllegalArgumentException();
            var envelope = json.readValue(bytes, Envelope.class);
            var session = request.getSession(false);
            if (session == null || envelope == null || envelope.keyId() == null) throw new IllegalArgumentException();
            var row = transactions.consume(envelope.keyId(), AccountSecrets.digest(session.getId()), request.getServletPath());
            if (row == null || !Instant.now().isBefore(row.expiresAt)) throw new IllegalArgumentException();
            var key = cipher.unwrap(row.wrappedKey, row.associatedData());
            byte[] plain = null;
            try {
                if (envelope.nonce() == null || envelope.ciphertext() == null) throw new IllegalArgumentException();
                var nonce = Base64.getDecoder().decode(envelope.nonce());
                var ciphertext = Base64.getDecoder().decode(envelope.ciphertext());
                if (nonce.length != 12 || ciphertext.length < 16 || ciphertext.length > 8192) throw new IllegalArgumentException();
                plain = PasswordKeyCipher.crypt(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), nonce, ciphertext,
                        "password-v1|" + row.id + "|" + row.purpose);
                var payload = json.readTree(plain);
                if (!payload.isObject()) throw new IllegalArgumentException();
                Map<String, String[]> parameters = new HashMap<>();
                if (request.getServletPath().equals("/api/v1/auth/login")) {
                    for (var name : List.of("identifier", "password")) {
                        var value = payload.get(name);
                        if (value == null || !value.isString()) throw new IllegalArgumentException();
                        parameters.put(name, new String[]{value.asText()});
                    }
                }
                decrypted = new PlainRequest(request, plain.clone(), parameters);
            } finally { Arrays.fill(key, (byte) 0); if (plain != null) Arrays.fill(plain, (byte) 0); }
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
            response.setStatus(400); response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"code\":\"PASSWORD_ENVELOPE_INVALID\",\"detail\":\"密码密钥已失效或请求无效，请重新提交\"}");
            return;
        }
        // 不捕获下游业务异常，避免把业务失败伪装成加密失败。
        try { chain.doFilter(decrypted, response); }
        finally { Arrays.fill(((PlainRequest) decrypted).body, (byte) 0); }
    }
    record Envelope(UUID keyId, String nonce, String ciphertext) {}
    /** 保留会话、请求头和路径，仅提供解密后的请求体及 Spring 登录所需的参数。 */
    private static final class PlainRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> parameters;
        PlainRequest(HttpServletRequest request, byte[] body, Map<String, String[]> parameters) { super(request); this.body = body; this.parameters = parameters; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public String getCharacterEncoding() { return "UTF-8"; }
        @Override public String getParameter(String name) { var values = parameters.get(name); return values == null ? null : values[0]; }
        @Override public String[] getParameterValues(String name) { return parameters.get(name); }
        @Override public Map<String, String[]> getParameterMap() { return Collections.unmodifiableMap(parameters); }
        @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(parameters.keySet()); }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous password request"); }
            };
        }
    }
}
