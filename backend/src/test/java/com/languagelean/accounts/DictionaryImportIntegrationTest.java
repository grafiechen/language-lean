package com.languagelean.accounts;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 multipart HTTP 验证导入预览、重复提示、权限隔离和确认发布。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:dictionary-import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql",
    "app.bootstrap-admin.username=importer", "app.bootstrap-admin.email=importer@example.com",
    "app.bootstrap-admin.password=Importer12!"
})
class DictionaryImportIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;

    /** 重复项必须逐条提示，确认前不可见，确认后只发布通过校验的新词条。 */
    @Test
    void previewsDuplicatesAndPublishesOnlyReadyEntries() throws Exception {
        accounts.saveAndFlush(UserAccountEntity.create("reader2", "reader2@example.com",
                encoder.encode("Reader12!"), Set.of(Role.USER)));
        var admin = login("importer", "Importer12!");
        var reader = login("reader2", "Reader12!");
        assertEquals(403, upload(reader, importFile()).statusCode());

        var previewResponse = upload(admin, importFile());
        assertEquals(200, previewResponse.statusCode(), previewResponse.body());
        var preview = json.readTree(previewResponse.body());
        assertEquals(4, preview.get("totalCount").asInt());
        assertEquals(2, preview.get("readyCount").asInt());
        assertEquals(1, preview.get("duplicateCount").asInt());
        assertEquals(1, preview.get("invalidCount").asInt());
        assertTrue(preview.get("rows").toString().contains("文件内重复"));
        assertEquals(0, searchTotal(reader, "猫"));

        var applied = post(admin, "/api/v1/admin/dictionary/imports/" + preview.get("id").asText() + "/apply",
                "{\"version\":" + preview.get("version").asLong() + "}");
        assertEquals(200, applied.statusCode(), applied.body());
        assertEquals(2, json.readTree(applied.body()).get("importedCount").asInt());
        assertEquals(1, searchTotal(reader, "猫"));
        assertEquals(1, searchTotal(reader, "犬"));
        var listing = json.readTree(get(reader, "/api/v1/dictionary?q=" + URLEncoder.encode("猫", StandardCharsets.UTF_8)).body());
        var detail = json.readTree(get(reader, "/api/v1/dictionary/" + listing.get("items").get(0).get("id").asText()).body());
        var sense = detail.get("content").get("senses").get(0);
        assertEquals("zh-Hans", sense.get("glossLanguage").asText());
        assertEquals("cat", sense.get("translations").get("en").get("text").asText());

        var repeated = json.readTree(upload(admin, importFile()).body());
        assertEquals(0, repeated.get("readyCount").asInt());
        assertEquals(3, repeated.get("duplicateCount").asInt());
        assertTrue(repeated.get("rows").toString().contains("基准词典中已存在"));
    }

    /** 测试文件同时包含有效、文件内重复和无效词条。 */
    private String importFile() {
        return """
                {"schemaVersion":1,"source":{"name":"测试开源词典","version":"2026.09","license":"CC0-1.0"},"entries":[
                  {"languageCode":"ja","scriptCode":"Jpan","written":"猫","readings":[{"reading":"ねこ","pronunciationText":"ねこ"}],"senses":[{"partOfSpeech":"名词","gloss":"猫","glossLanguage":"zh-Hans","translations":{"en":{"text":"cat","sourceName":"手工录入","license":"CC0-1.0"}},"examples":[]}]},
                  {"languageCode":"ja","scriptCode":"Jpan","written":"犬","readings":[{"reading":"いぬ","pronunciationText":"いぬ"}],"senses":[{"partOfSpeech":"名词","gloss":"狗","examples":[]}]},
                  {"languageCode":"ja","scriptCode":"Jpan","written":"猫","readings":[],"senses":[{"partOfSpeech":"名词","gloss":"重复","examples":[]}]},
                  {"languageCode":"ja","scriptCode":"Jpan","written":"空值","readings":[],"senses":[{"partOfSpeech":"名词","gloss":"","examples":[]}]}
                ]}
                """;
    }

    /** 生成浏览器等价的 multipart/form-data 请求并附带当前 CSRF。 */
    private HttpResponse<String> upload(HttpClient client, String content) throws Exception {
        var boundary = "----LanguageLeanBoundary";
        var output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var token = csrf(client);
        var request = HttpRequest.newBuilder(uri("/api/v1/admin/dictionary/imports/preview"))
                .header("X-XSRF-TOKEN", token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient login(String name, String password) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var token = csrf(client);
        var body = "identifier=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("X-XSRF-TOKEN", token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        return client;
    }
    private String csrf(HttpClient client) throws Exception {
        JsonNode body = json.readTree(get(client, "/api/v1/auth/csrf").body());
        return body.get("token").asText();
    }
    private HttpResponse<String> post(HttpClient client, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("X-XSRF-TOKEN", csrf(client))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    /** 查询参数按浏览器规则编码，并解析稳定分页响应验证公开可见性。 */
    private long searchTotal(HttpClient client, String query) throws Exception {
        var response = get(client, "/api/v1/dictionary?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).get("total").asLong();
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
