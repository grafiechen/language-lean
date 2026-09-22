package com.languagelean.accounts;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql",
    "app.bootstrap-admin.username=owner",
    "app.bootstrap-admin.email=owner@example.com",
    "app.bootstrap-admin.password=a-secure-test-password1!"
})
class AuthIntegrationTest {
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    @Value("${local.server.port}") int port;

    @Test
    void usernameAndEmailCanLoginIntoTheSameStableAccount() throws Exception {
        var usernameSession = client();
        var usernameMe = login(usernameSession, "owner");
        var emailSession = client();
        var emailMe = login(emailSession, "OWNER@EXAMPLE.COM");

        assertTrue(usernameMe.contains("\"username\":\"owner\""));
        assertTrue(usernameMe.contains("\"roles\":["));
        assertTrue(usernameMe.contains("\"ADMIN\""));
        assertEquals(extractId(usernameMe), extractId(emailMe));

        var csrf = csrf(usernameSession);
        // 通过真实 HTTP 验证长度和三类字符约束，失败请求不能改变旧密码。
        for (var invalid : new String[]{"abcde1!", "abcdefgh!", "abcdef12", "1234567!", "abcdef1 "}) {
            var rejected = postJson(usernameSession, "/api/v1/auth/password", csrf,
                    "{\"currentPassword\":\"a-secure-test-password1!\","
                            + "\"newPassword\":\"" + invalid + "\","
                            + "\"confirmation\":\"" + invalid + "\"}");
            assertEquals(400, rejected.statusCode());
        }
        var changed = postJson(usernameSession, "/api/v1/auth/password", csrf,
                "{\"currentPassword\":\"a-secure-test-password1!\","
                        + "\"newPassword\":\"abcdef1!\","
                        + "\"confirmation\":\"abcdef1!\"}");
        assertEquals(200, changed.statusCode());
        assertTrue(get(usernameSession, "/api/v1/auth/me").body().contains("\"mustChangePassword\":false"));
    }

    @Test
    void invalidCredentialsDoNotCreateASession() throws Exception {
        var client = client();
        var csrf = csrf(client);
        var response = postForm(client, "/api/v1/auth/login", csrf,
                "identifier=owner&password=wrong-password");
        assertEquals(401, response.statusCode());
        assertEquals(401, get(client, "/api/v1/auth/me").statusCode());
    }

    private String login(HttpClient client, String identifier) throws Exception {
        var csrf = csrf(client);
        var form = "identifier=" + URLEncoder.encode(identifier, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode("a-secure-test-password1!", StandardCharsets.UTF_8);
        assertEquals(200, postForm(client, "/api/v1/auth/login", csrf, form).statusCode());
        var me = get(client, "/api/v1/auth/me");
        assertEquals(200, me.statusCode());
        return me.body();
    }

    private Csrf csrf(HttpClient client) throws Exception {
        var response = get(client, "/api/v1/auth/csrf");
        assertEquals(200, response.statusCode());
        var matcher = TOKEN.matcher(response.body());
        assertTrue(matcher.find());
        return new Csrf("X-XSRF-TOKEN", matcher.group(1));
    }

    private HttpResponse<String> postForm(HttpClient client, String path, Csrf csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header(csrf.header(), csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(HttpClient client, String path, Csrf csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header(csrf.header(), csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient client() {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private String extractId(String body) {
        var matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
    private record Csrf(String header, String token) {}
}
