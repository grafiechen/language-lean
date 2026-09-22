package com.languagelean.accounts;

import com.languagelean.dictionary.*;
import com.languagelean.languages.LanguageAdminService;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 HTTP 验证权限与公开隔离，并覆盖版本冲突、恢复和封禁。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:dictionary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql",
    "app.bootstrap-admin.username=editor", "app.bootstrap-admin.email=editor@example.com",
    "app.bootstrap-admin.password=Editor12!"
})
class DictionaryIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired DictionaryService dictionary;
    @Autowired LanguageAdminService languages;

    /** 普通用户无法管理，草稿编辑与恢复不能提前影响用户看到的版本。 */
    @Test
    void completePublishingLifecycleAndAccessControl() throws Exception {
        accounts.saveAndFlush(UserAccountEntity.create("reader", "reader@example.com", encoder.encode("Reader12!"), Set.of(Role.USER)));
        var reader = login("reader", "Reader12!");
        var admin = login("editor", "Editor12!");
        assertEquals(401, get(client(), "/api/v1/admin/dictionary").statusCode());
        assertEquals(403, get(reader, "/api/v1/admin/dictionary").statusCode());
        assertEquals(403, post(reader, "/api/v1/admin/dictionary", Map.of()).statusCode());
        var created = post(admin, "/api/v1/admin/dictionary", new DictionaryService.Create("ja", "Jpan", "猫", content("猫")));
        assertEquals(200, created.statusCode(), created.body());
        var entry = json.readValue(created.body(), DictionaryService.AdminView.class);
        String adminPath = "/api/v1/admin/dictionary/" + entry.id();
        String publicPath = "/api/v1/dictionary/" + entry.id();
        assertEquals(404, get(reader, publicPath).statusCode());
        assertFalse(get(reader, "/api/v1/dictionary").body().contains("猫"));
        assertEquals(409, post(admin, "/api/v1/admin/dictionary",
                new DictionaryService.Create("ja", "Jpan", "猫", content("重复"))).statusCode());
        assertEquals(409, post(admin, adminPath + "/publish", new DictionaryService.Action(99L, "")).statusCode());

        entry = result(post(admin, adminPath + "/publish", new DictionaryService.Action(entry.version(), "首次确认")));
        assertEquals(1, entry.currentRevision());
        assertNull(entry.draft());
        assertEquals("猫", publicContent(reader, publicPath).senses().getFirst().gloss());

        entry = result(post(admin, adminPath + "/draft", new DictionaryService.Edit(entry.version(), content("家猫"))));
        assertEquals("猫", publicContent(reader, publicPath).senses().getFirst().gloss());
        assertEquals(409, post(admin, adminPath + "/draft", new DictionaryService.Edit(entry.version() - 1, content("过期内容"))).statusCode());
        entry = result(post(admin, adminPath + "/publish", new DictionaryService.Action(entry.version(), "补充释义")));
        assertEquals("家猫", publicContent(reader, publicPath).senses().getFirst().gloss());
        entry = result(post(admin, adminPath + "/history/1/restore", new DictionaryService.Action(entry.version(), "")));
        assertEquals("家猫", publicContent(reader, publicPath).senses().getFirst().gloss());
        entry = result(post(admin, adminPath + "/publish", new DictionaryService.Action(entry.version(), "恢复第一版")));
        assertEquals(3, entry.currentRevision());
        assertEquals("猫", publicContent(reader, publicPath).senses().getFirst().gloss());
        var history = json.readTree(get(admin, adminPath + "/history").body());
        assertEquals(3, history.get("total").asInt());
        assertEquals("家猫", history.get("items").get(1).get("content").get("senses").get(0).get("gloss").asText());
        assertEquals(403, get(reader, adminPath + "/history").statusCode());

        var ja = languages.list().stream().filter(l -> l.code().equals("ja")).findFirst().orElseThrow();
        var disabled = languages.save("ja", new LanguageAdminService.Edit("日语", "ja-JP", false, ja.version()));
        assertEquals(404, get(reader, publicPath).statusCode());
        languages.save("ja", new LanguageAdminService.Edit("日语", "ja-JP", true, disabled.version()));
        entry = result(post(admin, adminPath + "/ban", new DictionaryService.Action(entry.version(), "")));
        var banned = json.readTree(get(reader, publicPath).body());
        assertEquals("BANNED", banned.get("status").asText());
        assertTrue(banned.get("content").isNull());
        assertEquals(400, post(admin, adminPath + "/draft", new DictionaryService.Edit(entry.version(), content("不允许"))).statusCode());
    }

    /** 新语言、空释义发布限制与规范化身份去重均在服务端执行。 */
    @Test
    void validatesContentAndLanguageConfiguration() {
        assertThrows(ResponseStatusException.class,
                () -> languages.save("fr", new LanguageAdminService.Edit("法语", "bad locale", true, null)));
        var en = languages.save("en", new LanguageAdminService.Edit("英语", "en-US", true, null));
        assertTrue(en.enabled());
        assertThrows(ResponseStatusException.class,
                () -> languages.save("en", new LanguageAdminService.Edit("英语", "en-US", false, null)));
        var actor = UUID.randomUUID();
        var draft = dictionary.create(new DictionaryService.Create("en", "Latn", "ＡＢＣ",
                new DictionaryContent(1, List.of(), List.of(), "", "")), actor);
        assertThrows(ResponseStatusException.class,
                () -> dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), ""), actor));
        assertEquals(1, dictionary.list(true, "ABC", "en", 0).total());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> dictionary.create(new DictionaryService.Create("en", "Latn", "ABC", content("duplicate")), actor));
    }

    /** 测试快照包含读音、词义和例句，验证历史可恢复完整结构。 */
    private DictionaryContent content(String gloss) {
        return new DictionaryContent(1,
                List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ")),
                List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", gloss,
                        List.of(new DictionaryContent.Example(UUID.randomUUID(), "猫がいる。", "", "有一只猫。")))),
                "手工录入", "");
    }
    /** 校验管理员操作成功后读取新编辑版本。 */
    private DictionaryService.AdminView result(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readValue(response.body(), DictionaryService.AdminView.class);
    }
    /** 用户视图必须有成功状态，才能比较已公开内容。 */
    private DictionaryContent publicContent(HttpClient client, String path) throws Exception {
        var response = get(client, path);
        assertEquals(200, response.statusCode(), response.body());
        return json.readValue(response.body(), DictionaryService.PublicView.class).content();
    }
    /** 独立 Cookie 容器模拟不同用户浏览器会话。 */
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    /** 模拟带 CSRF 的真实表单登录。 */
    private HttpClient login(String name, String password) throws Exception {
        var client = client();
        var token = json.readTree(get(client, "/api/v1/auth/csrf").body()).get("token").asText();
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header("X-XSRF-TOKEN", token).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("identifier=" + name + "&password=" + password)).build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        return client;
    }
    /** 每次写请求使用当前会话的 CSRF 令牌。 */
    private HttpResponse<String> post(HttpClient client, String path, Object body) throws Exception {
        var token = json.readTree(get(client, "/api/v1/auth/csrf").body()).get("token").asText();
        var request = HttpRequest.newBuilder(uri(path)).header("X-XSRF-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
}
