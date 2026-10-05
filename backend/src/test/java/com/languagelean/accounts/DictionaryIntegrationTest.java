package com.languagelean.accounts;

import com.languagelean.dictionary.*;
import com.languagelean.languages.LanguageAdminService;
import com.languagelean.systemdictionary.SystemDictionaryService;
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
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql,classpath:db/migration/V20__password_transport_keys.sql,classpath:db/migration/V21__audio_generation_usage.sql",
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
    @Autowired SystemDictionaryService systemDictionaries;

    /** 多语种内容在草稿、公开和历史恢复中保留，外部出处必须安全且有来源。 */
    @Test
    void retainsTranslationsAndValidatesTheirProvenance() {
        var actor = UUID.randomUUID();
        var translation = new DictionaryContent.Translation("猫", "手工录入", "", "https://example.test/source");
        var example = new DictionaryContent.Example(UUID.randomUUID(), "猫がいます。", "猫がいます。", "There is a cat.", "en",
            Map.of("zh-Hans", new DictionaryContent.Translation("有一只猫。", "手工录入", "", "")),
            new DictionaryContent.Attribution("原创例句", "CC0", "", "", ""));
        var sense = new DictionaryContent.Sense(UUID.randomUUID(), "名词", "cat", List.of(example), "en", Map.of("zh-Hans", translation));
        var content = new DictionaryContent(1, List.of(), List.of(sense), "测试内容", "CC0");
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "译文验证词", content), actor);
        var published = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "译文验收"), actor);
        assertEquals(translation, dictionary.detail(draft.id()).content().senses().getFirst().translations().get("zh-Hans"));
        assertEquals(example, published.published().senses().getFirst().examples().getFirst());
        var restored = dictionary.restore(draft.id(), 1, new DictionaryService.Action(published.version(), ""), actor);
        assertEquals(content, restored.draft());
        var badTranslation = new DictionaryContent.Translation("猫", "手工录入", "", "javascript:alert(1)");
        assertThrows(ResponseStatusException.class, () -> dictionary.validatePersonalContent(new DictionaryContent(1, List.of(),
            List.of(new DictionaryContent.Sense(UUID.randomUUID(), "", "cat", List.of(), "en", Map.of("zh-Hans", badTranslation))), "", "")));
        assertThrows(ResponseStatusException.class, () -> dictionary.validatePersonalContent(new DictionaryContent(1, List.of(),
            List.of(new DictionaryContent.Sense(UUID.randomUUID(), "", "cat", List.of(), "en", Map.of("invalid language", translation))), "", "")));
    }

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

    /** 系统字典选项可以维护和停用，稳定 value 与陈旧版本都由服务端保护。 */
    @Test
    void maintainsSystemDictionaryOptions() {
        var sources = systemDictionaries.detail("CONTENT_SOURCE");
        assertEquals("手工录入", sources.items().getFirst().value());
        sources = systemDictionaries.createItem("CONTENT_SOURCE",
                new SystemDictionaryService.ItemEdit("JMdict", "JMdict", "日英开源词典", 20, true, null));
        var item = sources.items().stream().filter(value -> value.value().equals("JMdict")).findFirst().orElseThrow();
        sources = systemDictionaries.updateItem("CONTENT_SOURCE", item.id(),
                new SystemDictionaryService.ItemEdit("JMdict", "JMdict 开源词典", "日英开源词典", 20, false, item.version()));
        var updated = sources.items().stream().filter(value -> value.id().equals(item.id())).findFirst().orElseThrow();
        assertEquals("JMdict 开源词典", updated.displayName());
        assertFalse(updated.enabled());
        assertThrows(ResponseStatusException.class, () -> systemDictionaries.updateItem("CONTENT_SOURCE", item.id(),
                new SystemDictionaryService.ItemEdit("changed", "错误修改", "", 20, true, updated.version())));
        assertThrows(ResponseStatusException.class, () -> systemDictionaries.createItem("CONTENT_SOURCE",
                new SystemDictionaryService.ItemEdit("JMdict", "重复", "", 30, true, null)));
    }

    /** 假名与写法共用包含匹配，Unicode空白及通配符均按普通输入处理，匹配多个读音只返回一个词条。 */
    @Test
    void searchesPublishedReadingsLikeWrittenWords() {
        var actor = UUID.randomUUID();
        var content = new DictionaryContent(1, List.of(
                new DictionaryContent.Reading(UUID.randomUUID(), "いじめる", "いじめる"),
                new DictionaryContent.Reading(UUID.randomUUID(), "いじめ", "いじめ")),
                content("欺负；虐待").senses(), "手工录入", "");
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "虐める", content), actor);
        assertEquals(0, dictionary.list(false, "いじめ", "ja", 0).total());
        assertEquals(1, dictionary.list(true, "いじめ", "ja", 0).total());
        var published = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), ""), actor);
        for (var query : List.of("いじめる", "じめ", "\u3000いじめる\u3000", "虐め"))
            assertEquals(1, dictionary.list(false, query, "ja", 0).total(), query);
        assertEquals(0, dictionary.list(false, "%", "ja", 0).total());
        assertEquals(0, dictionary.list(false, "_", "ja", 0).total());
        assertEquals(0, dictionary.list(false, "\\", "ja", 0).total());
        assertEquals(0, dictionary.list(false, "欺负", "ja", 0).total());
        assertEquals(0, dictionary.list(false, "いじめ", "en", 0).total());
        var updated = new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "いじめなおす", "")), content.senses(), content.sourceName(), "");
        var saved = dictionary.save(published.id(), new DictionaryService.Edit(published.version(), updated), actor);
        assertEquals(0, dictionary.list(false, "なおす", "ja", 0).total());
        assertEquals(1, dictionary.list(true, "なおす", "ja", 0).total());
        var changed = dictionary.publish(saved.id(), new DictionaryService.Action(saved.version(), ""), actor);
        assertEquals(1, dictionary.list(false, "なおす", "ja", 0).total());
        var restored = dictionary.restore(changed.id(), 1, new DictionaryService.Action(changed.version(), ""), actor);
        assertEquals(1, dictionary.list(false, "なおす", "ja", 0).total());
        dictionary.publish(restored.id(), new DictionaryService.Action(restored.version(), ""), actor);
        assertEquals(0, dictionary.list(false, "なおす", "ja", 0).total());
        assertEquals(1, dictionary.list(false, "いじめる", "ja", 0).total());
        var halfWidth = dictionary.create(new DictionaryService.Create("ja", "Jpan", "珈琲試験", new DictionaryContent(1,
                List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ｺｰﾋｰ", "")), content.senses(), "手工录入", "")), actor);
        dictionary.publish(halfWidth.id(), new DictionaryService.Action(halfWidth.version(), ""), actor);
        assertEquals(1, dictionary.list(false, "コーヒー", "ja", 0).total());
        assertEquals(1, dictionary.list(false, "ｺｰﾋｰ", "ja", 0).total());
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
                .header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(PasswordTransportClient.seal(client, uri("/api/v1/auth/login"), json.writeValueAsString(java.util.Map.of("identifier", name, "password", password))))).build();
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
