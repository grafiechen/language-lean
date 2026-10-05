package com.languagelean.accounts;

import com.languagelean.dictionary.*;
import com.languagelean.learning.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 验证独立投稿快照、补充保留原文、陈旧审核阻断、来源清理和真实HTTP权限。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:dictionary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql,classpath:db/migration/V20__password_transport_keys.sql",
    "app.bootstrap-admin.username=editor", "app.bootstrap-admin.email=editor@example.com",
    "app.bootstrap-admin.password=Editor12!"
})
class ContributionIntegrationTest {
    @Autowired ContributionService contributions;
    @Autowired DictionaryService dictionary;
    @Autowired PrivateEntryService privateEntries;
    @Autowired PersonalContentService personal;
    @Autowired LearningService learning;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.languagelean.languages.LanguageAdminService languages;
    @Value("${local.server.port}") int port;

    /** 原文编辑后申请不变，幂等请求仍返回原申请，公开结果独立于私有来源。 */
    @Test void snapshotIdempotencyAndIndependentPublication() {
        UUID owner = owner(), reviewer = reviewer();
        var source = privateWord(owner, "冻结", content("原始投稿"));
        var book = learning.createWordbook(owner, new LearningService.CreateWordbook("投稿学习", ""));
        var item = learning.addPrivateEntry(owner, book.id(), source.id());
        var request = request(source); var submitted = contributions.submit(owner, request);
        assertEquals("PENDING_REVIEW", submitted.row().status());
        assertEquals(0, dictionary.list(false, source.written(), "", 0).total());
        privateEntries.save(owner, source.id(), new PrivateEntryService.Edit(source.version(), source.written(), content("后改的私人内容")));
        assertEquals(submitted.row().id(), contributions.submit(owner, request).row().id());
        assertEquals("原始投稿", contributions.detail(owner, submitted.row().id()).content().senses().getFirst().gloss());
        var published = contributions.approve(reviewer, submitted.row().id(), new ContributionService.Action(submitted.version(), "确认"));
        assertEquals("APPROVED", published.row().status());
        var publicEntry = dictionary.detail(published.row().publishedEntryId());
        assertEquals("USER_CONTRIBUTED", publicEntry.originType());
        assertEquals("原始投稿", publicEntry.content().senses().getFirst().gloss());
        assertEquals("后改的私人内容", privateEntries.detail(owner, source.id()).content().senses().getFirst().gloss());
        assertEquals(item.id(), learning.listItems(owner, book.id()).getFirst().id());
        var history = dictionary.history(publicEntry.id(), 0).items().getFirst();
        assertEquals(submitted.row().id(), history.contributionId()); assertEquals(owner, history.contributedBy());
        assertEquals(1, contributions.credits(publicEntry.id()).size());
        learning.deletePrivateEntry(owner, source.id());
        assertEquals("APPROVED", contributions.detail(owner, submitted.row().id()).row().status());
        assertEquals(publicEntry.id(), contributions.submit(owner, request).row().publishedEntryId());
        assertEquals("原始投稿", dictionary.detail(publicEntry.id()).content().senses().getFirst().gloss());
    }
    /** 同写法投稿只追加新子项；已有读音、释义和例句的身份与顺序保持不变。 */
    @Test void supplementPreservesBaseAndDeduplicatesContent() {
        UUID owner = owner(); var initial = content("旧释义"); var base = published("追加", initial);
        var extraExample = new DictionaryContent.Example(UUID.randomUUID(), "新例句。", "", "新译文");
        var oldSense = initial.senses().getFirst();
        var candidate = new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "　ねこ　", "ねこ")),
            List.of(new DictionaryContent.Sense(UUID.randomUUID(), oldSense.partOfSpeech(), oldSense.gloss(), List.of(oldSense.examples().getFirst(), extraExample)),
                new DictionaryContent.Sense(UUID.randomUUID(), "名词", "新释义", List.of())), "补充来源", "许可说明");
        var source = privateWord(owner, "same", candidate);
        source = privateEntries.save(owner, source.id(), new PrivateEntryService.Edit(source.version(), base.written(), candidate));
        var s = contributions.submit(owner, request(source));
        assertEquals("SUPPLEMENT", s.row().kind()); assertEquals(1, s.baseRevision());
        var result = contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), ""));
        assertEquals(base.id(), result.row().publishedEntryId());
        var merged = dictionary.detail(base.id()).content();
        assertEquals(initial.readings(), merged.readings()); assertEquals(initial.sourceName(), merged.sourceName());
        assertEquals(initial.senses().getFirst().id(), merged.senses().getFirst().id());
        assertEquals(initial.senses().getFirst().examples().getFirst(), merged.senses().getFirst().examples().getFirst());
        assertEquals(2, merged.senses().size()); assertEquals(2, merged.senses().getFirst().examples().size());
        assertNotEquals(extraExample.id(), merged.senses().getFirst().examples().get(1).id());
        assertEquals("补充来源", contributions.credits(base.id()).getFirst().sourceName());
    }
    /** 无新增补充不能制造空历史，审核失败后申请仍待审可拒绝。 */
    @Test void identicalSupplementAndPendingDuplicatesAreRejected() {
        UUID owner = owner(); var base = published("重复", content("相同"));
        var source = privateEntries.create(owner, new PrivateEntryService.Create("ja", "Jpan", base.written(), base.published()));
        var s = contributions.submit(owner, request(source));
        conflict(() -> contributions.submit(owner, request(source)));
        conflict(() -> contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), "")));
        assertEquals(1, dictionary.detail(base.id()).currentRevision());
        assertEquals("PENDING_REVIEW", contributions.detail(owner, s.row().id()).row().status());
        var rejected = contributions.reject(reviewer(), s.row().id(), new ContributionService.Action(s.version(), "内容已存在"));
        assertEquals("REJECTED", rejected.row().status());
        assertNotEquals(s.row().id(), contributions.submit(owner, request(source)).row().id());
    }
    /** 公开修订保持旧版直到通过；并发基于旧版的另一修订不能覆盖最新公开内容。 */
    @Test void revisionChecksBaseAndOmitsPrivateMetadata() {
        UUID owner = owner(), other = owner(); var base = published("修订", content("旧公开"));
        var a = publicItem(owner, base.id()); var b = publicItem(other, base.id());
        personal.save(owner, a.id(), new PersonalContentService.Save(0L, "本人新义", "秘密笔记", List.of("私人标签")));
        var first = contributions.submit(owner, request(owner, a.id()));
        var second = contributions.submit(other, request(other, b.id()));
        assertFalse(json.writeValueAsString(first).contains("秘密笔记"));
        assertFalse(json.writeValueAsString(first).contains("私人标签"));
        assertEquals("旧公开", dictionary.detail(base.id()).content().senses().getFirst().gloss());
        contributions.approve(reviewer(), first.row().id(), new ContributionService.Action(first.version(), ""));
        assertEquals("本人新义", dictionary.detail(base.id()).content().senses().getFirst().gloss());
        conflict(() -> contributions.approve(reviewer(), second.row().id(), new ContributionService.Action(second.version(), "")));
        assertEquals("PENDING_REVIEW", contributions.detail(other, second.row().id()).row().status());
        assertEquals("旧公开", contributions.detail(other, second.row().id()).baseContent().senses().getFirst().gloss());
        assertEquals(2, dictionary.history(base.id(), 0).total());
    }
    /** 陈旧来源、篡改幂等身份、不明确公开及空释义均在服务端阻断。 */
    @Test void validatesSourceVersionConsentPayloadAndOwnership() {
        UUID owner = owner(), other = owner(); var source = privateWord(owner, "校验", content("完整"));
        var request = request(source);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> contributions.submit(other, request)).getStatusCode().value());
        privateEntries.save(owner, source.id(), new PrivateEntryService.Edit(source.version(), source.written(), content("更新")));
        conflict(() -> contributions.submit(owner, request));
        var latest = privateEntries.detail(owner, source.id()); var valid = request(latest); var s = contributions.submit(owner, valid);
        var altered = new ContributionService.Submit(valid.id(), latest.id(), latest.version(), null, null, null, content("篡改"), "", true);
        conflict(() -> contributions.submit(owner, altered));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> contributions.detail(other, s.row().id())).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> contributions.withdraw(other, s.row().id(), new ContributionService.Action(s.version(), ""))).getStatusCode().value());
        conflict(() -> contributions.withdraw(owner, s.row().id(), new ContributionService.Action(99L, "")));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> contributions.reject(reviewer(), s.row().id(), new ContributionService.Action(s.version(), ""))).getStatusCode().value());
        contributions.withdraw(owner, s.row().id(), new ContributionService.Action(s.version(), ""));
        conflict(() -> contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), "")));
        var noConsent = new ContributionService.Submit(UUID.randomUUID(), latest.id(), latest.version(), null, null, null, latest.content(), "", false);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> contributions.submit(owner, noConsent)).getStatusCode().value());
        var empty = new ContributionService.Submit(UUID.randomUUID(), latest.id(), latest.version(), null, null, null, content(""), "", true);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> contributions.submit(owner, empty)).getStatusCode().value());
    }
    /** 来源删除彻底清除未公开快照，跨单词本第一次移除仍保留申请。 */
    @Test void deletesUnpublishedSnapshotsOnlyAtLastAssociation() {
        UUID owner = owner(); var base = published("清理", content("基准")); var item = publicItem(owner, base.id());
        var second = learning.createWordbook(owner, new LearningService.CreateWordbook("第二本", ""));
        learning.addDictionaryEntry(owner, second.id(), base.id());
        var firstBook = learning.listWordbooks(owner).stream().filter(book -> !book.id().equals(second.id())).findFirst().orElseThrow();
        var submission = contributions.submit(owner, request(owner, item.id()));
        learning.removeLearningItem(owner, firstBook.id(), item.id());
        assertEquals("PENDING_REVIEW", contributions.detail(owner, submission.row().id()).row().status());
        learning.deleteWordbook(owner, second.id());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> contributions.detail(owner, submission.row().id())).getStatusCode().value());
        var source = privateWord(owner, "未审清理", content("未公开正文")); var s = contributions.submit(owner, request(source));
        learning.deletePrivateEntry(owner, source.id());
        assertEquals(0, jdbc.queryForObject("select count(*) from dictionary_contribution_submission where id=?", Integer.class, s.row().id()));
    }
    /** 后台未发布草稿和封禁边界不能被投稿审核绕过。 */
    @Test void respectsDraftBanAndConcurrentNewIdentity() {
        UUID owner = owner(); var base = published("草稿", content("旧")); var item = publicItem(owner, base.id());
        var s = contributions.submit(owner, request(owner, item.id()));
        dictionary.save(base.id(), new DictionaryService.Edit(base.version(), content("后台未发布")), reviewer());
        conflict(() -> contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), "")));
        var current = dictionary.adminDetail(base.id()); dictionary.ban(base.id(), new DictionaryService.Action(current.version(), ""), reviewer());
        conflict(() -> contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), "")));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> contributions.credits(base.id())).getStatusCode().value());
        var source = privateWord(owner, "身份冲突", content("私人")); var fresh = contributions.submit(owner, request(source));
        var added = dictionary.create(new DictionaryService.Create("ja", "Jpan", source.written(), content("其他新增")), reviewer());
        dictionary.publish(added.id(), new DictionaryService.Action(added.version(), ""), reviewer());
        conflict(() -> contributions.approve(reviewer(), fresh.row().id(), new ContributionService.Action(fresh.version(), "")));
        assertEquals(1, dictionary.list(false, source.written(), "", 0).total());
    }
    /** 两个管理员同时通过只能生成一个公开词条和一条发布历史。 */
    @Test void concurrentApprovalPublishesOnce() throws Exception {
        UUID owner = owner(), reviewer = reviewer(); var s = contributions.submit(owner, request(privateWord(owner, "并发", content("并发"))));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> decision = () -> { gate.await(); try { contributions.approve(reviewer, s.row().id(), new ContributionService.Action(s.version(), "")); return 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); } };
            var a = pool.submit(decision); var b = pool.submit(decision); gate.countDown();
            var outcomes = new ArrayList<>(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))); Collections.sort(outcomes);
            assertEquals(List.of(200, 409), outcomes);
        }
        var published = contributions.detail(owner, s.row().id());
        assertEquals(1, dictionary.history(published.row().publishedEntryId(), 0).total());
    }
    /** 同一请求同时上传也返回相同申请，而不是把第二次网络请求误报成重复投稿。 */
    @Test void concurrentIdenticalSubmissionIsIdempotent() throws Exception {
        UUID owner = owner(); var request = request(privateWord(owner, "同时上传", content("正文"))); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<UUID> submit = () -> { gate.await(); return contributions.submit(owner, request).row().id(); };
            var a = pool.submit(submit); var b = pool.submit(submit); gate.countDown();
            assertEquals(request.id(), a.get(10, TimeUnit.SECONDS)); assertEquals(request.id(), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, contributions.list(owner, "PENDING_REVIEW", 0).total());
    }
    /** 审核时也重新检查语言启用状态，禁用后失败不改变公开词典或审核状态。 */
    @Test void disabledLanguageBlocksApprovalAndIncompleteContentFailsSubmission() {
        UUID owner = owner(); var language = languages.save("pt", new com.languagelean.languages.LanguageAdminService.Edit("葡萄牙语", "pt-PT", true, null));
        var source = privateEntries.create(owner, new PrivateEntryService.Create("pt", "Latn", "palavra", content("完整释义")));
        var s = contributions.submit(owner, request(source));
        languages.save("pt", new com.languagelean.languages.LanguageAdminService.Edit("葡萄牙语", "pt-PT", false, language.version()));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), ""))).getStatusCode().value());
        assertEquals("PENDING_REVIEW", contributions.detail(owner, s.row().id()).row().status());
        var valid = privateWord(owner, "格式", content("完整"));
        var malformed = new DictionaryContent(1, null, List.of(), "手工录入", "");
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> contributions.submit(owner,
            new ContributionService.Submit(UUID.randomUUID(), valid.id(), valid.version(), null, null, null, malformed, "", true))).getStatusCode().value());
    }
    /** 未来账号注销清除私人数据时，补充来源和许可仍属于独立的公开历史。 */
    @Test void publicAttributionSurvivesContributorAccountDeletion() {
        UUID owner = owner(); var base = published("出处保留", content("原基准"));
        var supplemental = new DictionaryContent(1, List.of(), List.of(new DictionaryContent.Sense(UUID.randomUUID(), "", "追加的公开内容", List.of())), "自写来源", "原创许可");
        var source = privateEntries.create(owner, new PrivateEntryService.Create("ja", "Jpan", base.written(), supplemental));
        var s = contributions.submit(owner, request(source)); contributions.approve(reviewer(), s.row().id(), new ContributionService.Action(s.version(), ""));
        jdbc.update("delete from user_account where id=?", owner);
        var credits = contributions.credits(base.id()); assertEquals("自写来源", credits.getFirst().sourceName()); assertEquals("原创许可", credits.getFirst().license());
        var history = dictionary.history(base.id(), 0).items().getFirst(); assertEquals(owner, history.contributedBy()); assertEquals("原创许可", history.contributionLicense());
        assertEquals(2, dictionary.detail(base.id()).content().senses().size());
    }
    /** 真实Cookie/CSRF/角色及表单账号头阻止未登录、越权和跨账号投稿读取。 */
    @Test void httpEndpointsEnforceRoleOwnerAndCsrf() throws Exception {
        UUID owner = owner(), other = owner(); var source = privateWord(owner, "HTTP", content("HTTP正文"));
        var user = login(accounts.findById(owner).orElseThrow().getUsername(), "Reader12!");
        var foreign = login(accounts.findById(other).orElseThrow().getUsername(), "Reader12!"); var admin = login("editor", "Editor12!");
        assertEquals(401, get(client(), "/api/v1/contributions").statusCode());
        assertEquals(403, get(user, "/api/v1/admin/contributions").statusCode());
        var request = request(source);
        assertEquals(409, post(user, "/api/v1/contributions", request, other, true).statusCode());
        assertEquals(403, post(user, "/api/v1/contributions", request, owner, false).statusCode());
        var response = post(user, "/api/v1/contributions", request, owner, true); assertEquals(200, response.statusCode(), response.body());
        var s = json.readValue(response.body(), ContributionService.View.class); String path = "/api/v1/contributions/" + s.row().id();
        assertEquals(404, get(foreign, path).statusCode());
        assertFalse(get(foreign, "/api/v1/contributions").body().contains(s.row().id().toString()));
        assertEquals(403, post(user, "/api/v1/admin/contributions/" + s.row().id() + "/approve", new ContributionService.Action(s.version(), ""), owner, true).statusCode());
        var accepted = post(admin, "/api/v1/admin/contributions/" + s.row().id() + "/approve", new ContributionService.Action(s.version(), ""), reviewer(), true);
        assertEquals(200, accepted.statusCode(), accepted.body());
        var published = json.readValue(accepted.body(), ContributionService.View.class);
        var credits = get(foreign, "/api/v1/dictionary/" + published.row().publishedEntryId() + "/contributions");
        assertEquals(200, credits.statusCode()); assertFalse(credits.body().contains(owner.toString())); assertFalse(credits.body().contains(source.id().toString()));
    }
    /** 独立账号和唯一写法使各测试互不影响。 */
    private UUID owner() { String name = "contributor-" + UUID.randomUUID(); return accounts.saveAndFlush(UserAccountEntity.create(name, name + "@example.com", encoder.encode("Reader12!"), Set.of(Role.USER))).getId(); }
    private UUID reviewer() { return accounts.findAll().stream().filter(a -> a.getUsername().equals("editor")).findFirst().orElseThrow().getId(); }
    private PrivateEntryService.View privateWord(UUID owner, String prefix, DictionaryContent content) { return privateEntries.create(owner, new PrivateEntryService.Create("ja", "Jpan", prefix + UUID.randomUUID(), content)); }
    private DictionaryService.AdminView published(String prefix, DictionaryContent content) { var e = dictionary.create(new DictionaryService.Create("ja", "Jpan", prefix + UUID.randomUUID(), content), reviewer()); return dictionary.publish(e.id(), new DictionaryService.Action(e.version(), ""), reviewer()); }
    private LearningService.LearningItemView publicItem(UUID owner, UUID entry) { var b = learning.createWordbook(owner, new LearningService.CreateWordbook("投稿本", "")); return learning.addDictionaryEntry(owner, b.id(), entry); }
    private ContributionService.Submit request(PrivateEntryService.View source) { return new ContributionService.Submit(UUID.randomUUID(), source.id(), source.version(), null, null, null, source.content(), "明确投稿", true); }
    private ContributionService.Submit request(UUID owner, UUID item) { var source = personal.detail(owner, item); return new ContributionService.Submit(UUID.randomUUID(), null, null, item, source.personal().revision(), source.entry().currentRevision(), source.entry().content(), "修订", true); }
    private DictionaryContent content(String gloss) { return new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ")), List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", gloss, List.of(new DictionaryContent.Example(UUID.randomUUID(), "猫がいる。", "", "有猫。")))), "手工录入", ""); }
    private void conflict(Runnable action) { assertEquals(409, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value()); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private HttpResponse<String> get(HttpClient client, String path) throws Exception { return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpClient login(String name, String password) throws Exception {
        var c = client(); var token = json.readTree(get(c, "/api/v1/auth/csrf").body()).get("token").asText();
        assertEquals(200, c.send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString(PasswordTransportClient.seal(c, uri("/api/v1/auth/login"), json.writeValueAsString(java.util.Map.of("identifier", name, "password", password))))).build(), HttpResponse.BodyHandlers.ofString()).statusCode()); return c;
    }
    private HttpResponse<String> post(HttpClient c, String path, Object body, UUID owner, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-Learning-Account", owner.toString());
        if (csrf) builder.header("X-XSRF-TOKEN", json.readTree(get(c, "/api/v1/auth/csrf").body()).get("token").asText());
        return c.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
