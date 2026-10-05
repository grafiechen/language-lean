package com.languagelean.accounts;

import com.languagelean.dictionary.DictionaryContent;
import com.languagelean.dictionary.DictionaryService;
import com.languagelean.learning.LearningService;
import com.languagelean.learning.ReviewService;
import com.languagelean.learning.PersonalContentService;
import com.languagelean.learning.PrivateEntryService;
import com.languagelean.reviews.domain.Rating;
import com.languagelean.sync.ReviewSubmission;
import java.time.Instant;
import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证单词本分类、跨本共享进度、最后关联删除和完整重置规则。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:learning;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql,classpath:db/migration/V20__password_transport_keys.sql",
    "app.bootstrap-admin.username=editor", "app.bootstrap-admin.email=editor@example.com",
    "app.bootstrap-admin.password=Editor12!"
})
class LearningIntegrationTest {
    @Autowired LearningService learning;
    @Autowired DictionaryService dictionary;
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReviewService reviews;
    @Autowired PersonalContentService personal;
    @Autowired PrivateEntryService privateEntries;
    @Autowired com.languagelean.learning.LearningExportService exports;
    @Autowired com.languagelean.learning.LearningCsvImportService csvImports;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Value("${local.server.port}") int port;

    /** CSV只新增个人内容；重复、跨本和重试保留同一份FSRS与笔记，预检不写数据。 */
    @Test void csvImportsPreviewDuplicatesAndPreserveSharedProgress() {
        var f = fixture(); var other = fixture();
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "原释义", "保留笔记", List.of("旧标签")));
        var original = personal.detail(f.userId(), f.item().id());
        reviews.submit(f.userId(), submission(f.item(), Instant.parse("2026-09-30T01:00:00Z"), "0", null, Rating.HARD));
        var book = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("CSV新本", ""));
        var word = "导入新词" + UUID.randomUUID();
        var bytes = (dictionary.detail(f.item().dictionaryEntryId()).written() + ",ねこ,禁止覆盖,新标签\n"
            + word + ",よむ,\"逗号,释义\",N2|生活,例文です。,,例句译文\n"
            + "　" + word + "　,よむ,重复\n"
            + "缺少释义,,\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var beforeCount = learning.listItems(f.userId(), book.id()).size();
        var preview = csvImports.preview(f.userId(), book.id(), bytes, "zh-Hans");
        assertEquals(1, preview.added()); assertEquals(1, preview.linked()); assertEquals(1, preview.skipped()); assertEquals(1, preview.errors());
        assertEquals(beforeCount, learning.listItems(f.userId(), book.id()).size());
        rejects(404, () -> csvImports.preview(other.userId(), book.id(), bytes, "zh-Hans"));
        rejects(409, () -> csvImports.confirm(f.userId(), book.id(), bytes, "en", preview.fileHash()));
        assertEquals(0, learning.listItems(f.userId(), book.id()).size());
        var done = csvImports.confirm(f.userId(), book.id(), bytes, "zh-Hans", preview.fileHash());
        assertTrue(done.committed()); assertEquals(2, learning.listItems(f.userId(), book.id()).size());
        var shared = learning.listItems(f.userId(), book.id()).stream().filter(row -> row.id().equals(f.item().id())).findFirst().orElseThrow();
        assertEquals(1, shared.reviewCount()); assertTrue(shared.automaticEarFocus());
        assertEquals(original.personal(), personal.detail(f.userId(), shared.id()).personal());
        var added = learning.listItems(f.userId(), book.id()).stream().filter(row -> row.written().equals(word)).findFirst().orElseThrow();
        assertNotNull(added.personalCustomEntryId()); var content = personal.detail(f.userId(), added.id());
        assertEquals("zh-Hans", content.entry().content().senses().getFirst().glossLanguage());
        assertEquals("逗号,释义", content.entry().content().senses().getFirst().gloss());
        assertEquals("", content.entry().content().senses().getFirst().examples().getFirst().pronunciationText());
        assertEquals(List.of("N2", "生活"), content.personal().tags());
        var repeat = csvImports.confirm(f.userId(), book.id(), bytes, "zh-Hans", preview.fileHash());
        assertEquals(0, repeat.added()); assertEquals(0, repeat.linked()); assertEquals(3, repeat.skipped());
        assertEquals(1, learning.listItems(f.userId(), f.bookId()).getFirst().reviewCount());
    }

    /** 新公共引用保存CSV覆盖，确认前新增的本人私人内容只能复用，不能被预检快照覆盖。 */
    @Test void csvPublicReferencesAndConcurrentPrivateChangesStayPersonal() {
        var f = fixture(); var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "公开CSV" + UUID.randomUUID(), content()), f.userId());
        dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "CSV"), f.userId());
        var publicBefore = dictionary.detail(draft.id()).content();
        var privateWord = "私人CSV" + UUID.randomUUID();
        var bytes = (draft.written() + ",こじん,我的释义\n" + privateWord + ",,,\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 正常新增公共身份保存带语言的词义覆盖；基准词典保持完全一致。
        var preview = csvImports.preview(f.userId(), f.bookId(), bytes, "en");
        assertEquals(1, preview.added()); assertEquals(1, preview.errors());
        csvImports.confirm(f.userId(), f.bookId(), bytes, "en", preview.fileHash());
        var item = learning.listItems(f.userId(), f.bookId()).stream().filter(row -> draft.id().equals(row.dictionaryEntryId())).findFirst().orElseThrow();
        assertEquals("我的释义", personal.detail(f.userId(), item.id()).entry().content().senses().getFirst().gloss());
        assertEquals("en", personal.detail(f.userId(), item.id()).entry().content().senses().getFirst().glossLanguage());
        assertEquals(publicBefore, dictionary.detail(draft.id()).content());
        var privateBytes = (privateWord + ",こじん,文件释义").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var prior = csvImports.preview(f.userId(), f.bookId(), privateBytes, "zh-Hans");
        var privateEntry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", privateWord, content()));
        var committed = csvImports.confirm(f.userId(), f.bookId(), privateBytes, "zh-Hans", prior.fileHash());
        assertEquals(0, committed.added()); assertEquals(1, committed.linked());
        assertEquals("cat", privateEntries.detail(f.userId(), privateEntry.id()).content().senses().getFirst().gloss());
    }

    /** 跨本手动重点共享，取消手动重点不抹除自动耳词或FSRS，其他用户无权修改。 */
    @Test
    void manualEarFocusSharesAcrossBooksWithoutChangingProgress() {
        var f = fixture(); var other = fixture();
        var second = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("耳词分类", ""));
        learning.addDictionaryEntry(f.userId(), second.id(), f.item().dictionaryEntryId());
        reviews.submit(f.userId(), submission(f.item(), Instant.parse("2026-09-30T01:00:00Z"), "0", null, Rating.HARD));
        var focused = learning.setManualEarFocus(f.userId(), f.item().id(), true);
        assertTrue(learning.listItems(f.userId(), second.id()).getFirst().manualEarFocus());
        var cleared = learning.setManualEarFocus(f.userId(), f.item().id(), false);
        assertFalse(cleared.manualEarFocus()); assertTrue(cleared.automaticEarFocus());
        assertEquals(focused.fsrsState(), cleared.fsrsState()); assertEquals(focused.reviewCount(), cleared.reviewCount());
        rejects(404, () -> learning.setManualEarFocus(other.userId(), f.item().id(), true));
        rejects(400, () -> learning.setManualEarFocus(f.userId(), f.item().id(), null));
        learning.resetWordbook(f.userId(), second.id());
        var reset = learning.listItems(f.userId(), f.bookId()).getFirst();
        assertFalse(reset.manualEarFocus()); assertFalse(reset.automaticEarFocus()); assertEquals(0, reset.reviewCount());
    }

    /** 完整导出只包含本人，超过50次的历史不截断，封禁公开词后仍可备份私人笔记。 */
    @Test
    void exportsOwnedLearningAndFullHistoryWithoutChangingProgress() {
        var f = fixture(); var other = fixture();
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "我的释义", "私人笔记", List.of("备份")));
        var item = f.item();
        for (int index = 0; index < 51; index++) {
            var time = Instant.parse("2026-09-25T00:00:00Z").plusSeconds(index * 3600L);
            var result = reviews.submit(f.userId(), submission(item, time, item.progressVersion(), item.lastReviewEventId(), Rating.GOOD));
            item = learning.listItems(f.userId(), f.bookId()).getFirst();
        }
        var before = item;
        var published = dictionary.adminDetail(f.item().dictionaryEntryId());
        dictionary.ban(published.id(), new DictionaryService.Action(published.version(), "导出测试"), f.userId());
        var exported = exports.export(f.userId());
        assertEquals(f.userId(), exported.userId()); assertEquals(1, exported.items().size());
        assertEquals("私人笔记", exported.items().getFirst().personal().notes());
        assertEquals(51, exported.reviews().size()); assertEquals(51, exported.items().getFirst().reviewCount());
        assertTrue(exported.items().stream().noneMatch(value -> value.id().equals(other.item().id())));
        assertEquals(before.progressVersion(), learning.listItems(f.userId(), f.bookId()).getFirst().progressVersion());
        var serialized = json.writeValueAsString(exported);
        assertFalse(serialized.contains("passwordHash")); assertFalse(serialized.contains("@example"));
    }

    /** 真实 HTTP 验证 JSON 契约、CSRF、认证归属和重复上传确认。 */
    @Test
    void reviewApiRequiresAuthenticationAndUsesSessionAccount() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var root = "http://localhost:" + port;
        var unauthenticated = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/items/" + UUID.randomUUID() + "/reviews")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthenticated.statusCode());
        var csrf = httpToken(client, root);
        var login = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(PasswordTransportClient.sealForm(client, URI.create(root + "/api/v1/auth/login"), "identifier=editor&password=Editor12%21"))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode());
        csrf = httpToken(client, root);
        var foreign = fixture();
        var me = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var ownerId = UUID.fromString(json.readTree(me.body()).get("id").asText());
        var book = learning.createWordbook(ownerId, new LearningService.CreateWordbook("http-" + UUID.randomUUID(), ""));
        var item = learning.addDictionaryEntry(ownerId, book.id(), foreign.item().dictionaryEntryId());
        var event = submission(item, Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD);
        assertEquals(403, httpSubmit(client, root, null, event).statusCode());
        assertEquals(404, httpSubmit(client, root, csrf, submission(foreign.item(), event.completedAt(), "0", null, Rating.GOOD)).statusCode());
        var accepted = httpSubmit(client, root, csrf, event);
        assertEquals(200, accepted.statusCode());
        assertEquals("APPLIED", json.readTree(accepted.body()).get("status").asText());
        var itemResponse = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/wordbooks/" + book.id() + "/items")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, itemResponse.statusCode());
        var offlineCard = json.readTree(itemResponse.body()).get(0);
        assertTrue(offlineCard.get("fsrsState").asText().contains("stability"));
        assertEquals(21, offlineCard.get("scheduler").get("parameters").size());
        assertFalse(offlineCard.get("scheduler").get("enableFuzzing").asBoolean());
        assertEquals(1, offlineCard.get("scheduler").get("schemaVersion").asInt());
        assertEquals("DUPLICATE", json.readTree(httpSubmit(client, root, csrf, event).body()).get("status").asText());
        var historyResponse = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/items/" + item.id() + "/reviews")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, historyResponse.statusCode());
        assertEquals(1, json.readTree(historyResponse.body()).size());
        var check = new LearningService.ReconcileRequest(List.of(item.id(), foreign.item().id()), List.of(book.id(), foreign.bookId()));
        var body = json.writeValueAsString(check);
        var withoutCsrf = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/reconcile"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, withoutCsrf.statusCode());
        var snapshot = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/reconcile"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, snapshot.statusCode());
        var view = json.readTree(snapshot.body());
        assertEquals(ownerId.toString(), view.get("userId").asText());
        assertEquals(1, view.get("items").size());
        assertEquals(foreign.item().id().toString(), view.get("missingItemIds").get(0).asText());
        assertEquals(foreign.bookId().toString(), view.get("missingBookIds").get(0).asText());
        learning.resetWordbook(ownerId, book.id());
        var stale = httpSubmit(client, root, csrf, event);
        assertEquals(409, stale.statusCode());
        assertEquals("PROGRESS_RESET", json.readTree(stale.body()).get("code").asText());
        var missing = httpSubmit(client, root, csrf, submission(foreign.item(), event.completedAt(), "0", null, Rating.GOOD));
        assertEquals("LEARNING_ITEM_MISSING", json.readTree(missing.body()).get("code").asText());
    }

    /** 获取当前会话 CSRF，登录后必须重新读取以适应会话轮换。 */
    private String httpToken(HttpClient client, String root) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body()).get("token").asText();
    }

    /** 不注入账户字段，由后端会话确定提交归属。 */
    private HttpResponse<String> httpSubmit(HttpClient client, String root, String csrf, ReviewSubmission event) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/reviews"))
                .header("Content-Type", "application/json");
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event))).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 每词完成只调度一次，Again 重试保留最差评分；额外训练同样更新 FSRS。 */
    @Test
    void schedulesFromBaselineAndDeduplicatesRetries() {
        var f = fixture();
        var time = Instant.parse("2026-09-30T12:00:00Z");
        var first = submission(f.item(), time, "0", null, Rating.AGAIN, Rating.GOOD);
        var applied = reviews.submit(f.userId(), first);
        assertEquals("APPLIED", applied.status());
        assertEquals(time.plusSeconds(60), applied.nextReviewAt());
        assertEquals(1, applied.reviewCount());
        assertEquals(1, applied.lapseCount());
        assertTrue(applied.automaticEarFocus());
        assertTrue(applied.fsrsState().contains("stability"));
        assertEquals("DUPLICATE", reviews.submit(f.userId(), first).status());
        assertEquals(1, reviews.history(f.userId(), f.item().id()).size());
        var extra = submission(f.item(), time.plusSeconds(30), applied.progressVersion(), first.eventId(), Rating.GOOD);
        var next = reviews.submit(f.userId(), extra);
        assertEquals(2, next.reviewCount());
        assertTrue(next.nextReviewAt().isAfter(next.lastReviewedAt()));
        assertNotEquals(applied.fsrsState(), next.fsrsState());
    }

    /** 昨天离线完成的记录今天才上传，只保留历史，不回退今天的当前卡片。 */
    @Test
    void lateOfflineAnswerCannotOverwriteNewerProgress() {
        var f = fixture();
        var newer = submission(f.item(), Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD);
        var current = reviews.submit(f.userId(), newer);
        var older = submission(f.item(), Instant.parse("2026-09-29T12:00:00Z"), "0", null, Rating.HARD);
        var late = reviews.submit(f.userId(), older);
        assertEquals("HISTORICAL", late.status());
        assertEquals(current.fsrsState(), late.fsrsState());
        assertEquals(current.nextReviewAt(), late.nextReviewAt());
        assertEquals(newer.eventId(), late.lastReviewEventId());
        assertEquals(2, late.reviewCount());
        assertEquals(2, reviews.history(f.userId(), f.item().id()).size());
        assertEquals(newer.completedAt(), reviews.history(f.userId(), f.item().id()).getFirst().completedAt());
    }

    /** 相同时间戳按 UUID 稳定排序；后续提交必须明确引用有歧义的基准事件。 */
    @Test
    void resolvesEqualTimestampsDeterministically() {
        var f = fixture();
        var time = Instant.parse("2026-09-30T12:00:00Z");
        var high = withEvent(submission(f.item(), time, "0", null, Rating.GOOD),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        var low = withEvent(submission(f.item(), time, "0", null, Rating.HARD),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var current = reviews.submit(f.userId(), high);
        var late = reviews.submit(f.userId(), low);
        assertEquals("HISTORICAL", late.status());
        assertEquals(high.eventId(), late.lastReviewEventId());
        assertEquals(current.fsrsState(), late.fsrsState());
        rejects(409, () -> reviews.submit(f.userId(), submission(f.item(), time.plusSeconds(60), time.toString(), null, Rating.GOOD)));
        assertEquals("APPLIED", reviews.submit(f.userId(), submission(f.item(), time.plusSeconds(60), time.toString(), high.eventId(), Rating.GOOD)).status());
    }

    /** 重置清空全部共享进度和历史，拒绝旧代际的首次上传与重复上传。 */
    @Test
    void resetInvalidatesCachedEventsAndClearsSharedHistory() {
        var f = fixture();
        var second = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("shared-" + UUID.randomUUID(), ""));
        learning.addDictionaryEntry(f.userId(), second.id(), f.item().dictionaryEntryId());
        var event = submission(f.item(), Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.HARD);
        reviews.submit(f.userId(), event);
        learning.resetWordbook(f.userId(), f.bookId());
        var reset = learning.listItems(f.userId(), second.id()).getFirst();
        assertEquals(0, reset.reviewCount());
        assertEquals("0", reset.progressVersion());
        assertFalse(reset.automaticEarFocus());
        assertNull(reset.lastReviewEventId());
        assertTrue(reviews.history(f.userId(), reset.id()).isEmpty());
        rejects(409, () -> reviews.submit(f.userId(), event));
        rejects(409, () -> reviews.submit(f.userId(), submission(f.item(), event.completedAt().plusSeconds(60), "0", null, Rating.GOOD)));
    }

    /** 删除最后关联级联删除历史，重新加入获得新学习身份，其他账户不能上传。 */
    @Test
    void deletionAndAccountBoundariesPreventRevivingOldProgress() {
        var f = fixture();
        var event = submission(f.item(), Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD);
        reviews.submit(f.userId(), event);
        rejects(404, () -> reviews.submit(UUID.randomUUID(), event));
        rejects(404, () -> reviews.history(UUID.randomUUID(), f.item().id()));
        learning.removeDictionaryEntry(f.userId(), f.bookId(), f.item().dictionaryEntryId());
        assertEquals(0, jdbc.queryForObject("select count(*) from learning_review_event where learning_item_id = ?", Integer.class, f.item().id()));
        var fresh = learning.addDictionaryEntry(f.userId(), f.bookId(), f.item().dictionaryEntryId());
        assertNotEquals(f.item().id(), fresh.id());
        assertEquals(0, fresh.reviewCount());
        rejects(404, () -> reviews.submit(f.userId(), event));
    }

    /** 未完成、未知题型、未来时间、缺失基准和事件改写都不能改变进度。 */
    @Test
    void rejectsInvalidSubmissionsWithoutChangingProgress() {
        var f = fixture();
        var time = Instant.parse("2026-09-30T12:00:00Z");
        rejects(400, () -> reviews.submit(f.userId(), submission(f.item(), time, "0", null, Rating.AGAIN)));
        rejects(400, () -> reviews.submit(f.userId(), submission(f.item(), time, "0", null, Rating.GOOD, Rating.HARD)));
        rejects(400, () -> reviews.submit(f.userId(), submission(f.item(), Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS), "0", null, Rating.GOOD)));
        rejects(409, () -> reviews.submit(f.userId(), submission(f.item(), time, time.minusSeconds(60).toString(), null, Rating.GOOD)));
        assertEquals(0, learning.listItems(f.userId(), f.bookId()).getFirst().reviewCount());
        var accepted = submission(f.item(), time, "0", null, Rating.GOOD);
        reviews.submit(f.userId(), accepted);
        rejects(409, () -> reviews.submit(f.userId(), withEvent(submission(f.item(), time, "0", null, Rating.HARD), accepted.eventId())));
        var duplicatedAttempt = new ReviewSubmission(UUID.randomUUID(), accepted.attemptId(), accepted.learningItemId(), accepted.progressEpoch(),
                "0", time.toString(), time, null, accepted.results());
        rejects(409, () -> reviews.submit(f.userId(), duplicatedAttempt));
        assertEquals(1, learning.listItems(f.userId(), f.bookId()).getFirst().reviewCount());
    }

    /** 同时上传同一事件时，行锁保证只有一次真正写入。 */
    @Test
    void concurrentUploadIsIdempotent() throws Exception {
        var f = fixture();
        var event = submission(f.item(), Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> task = () -> { start.await(); return reviews.submit(f.userId(), event).status(); };
            var a = executor.submit(task);
            var b = executor.submit(task);
            start.countDown();
            assertEquals(Set.of("APPLIED", "DUPLICATE"), Set.of(a.get(), b.get()));
        }
        assertEquals(1, learning.listItems(f.userId(), f.bookId()).getFirst().reviewCount());
    }

    /** 为每个用例创建独立账户、公开词条和单词本，避免测试顺序互相影响。 */
    private Fixture fixture() {
        var suffix = UUID.randomUUID().toString();
        var user = accounts.saveAndFlush(UserAccountEntity.create("u-" + suffix, suffix + "@example.com", encoder.encode("Learner12!"), Set.of(Role.USER)));
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "test-" + suffix, content()), user.getId());
        var published = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试发布"), user.getId());
        var book = learning.createWordbook(user.getId(), new LearningService.CreateWordbook("test", ""));
        return new Fixture(user.getId(), book.id(), learning.addDictionaryEntry(user.getId(), book.id(), published.id()));
    }

    /** 构造带完整重试记录的毫秒时间戳事件。 */
    private ReviewSubmission submission(LearningService.LearningItemView item, Instant completed, String base, UUID baseId, Rating... ratings) {
        var trials = java.util.stream.IntStream.range(0, ratings.length).mapToObj(index ->
                new ReviewSubmission.Trial(UUID.randomUUID(), completed.minusSeconds(ratings.length - index - 1), ratings[index])).toList();
        return new ReviewSubmission(UUID.randomUUID(), UUID.randomUUID(), item.id(), item.progressEpoch(), base,
                completed.toString(), completed, baseId, List.of(new ReviewSubmission.TypeResult("LISTEN_RECALL", 1, trials)));
    }

    /** 为同时间戳排序和内容冲突用例保留指定事件标识。 */
    private ReviewSubmission withEvent(ReviewSubmission event, UUID id) {
        return new ReviewSubmission(id, event.attemptId(), event.learningItemId(), event.progressEpoch(), event.baseVersion(),
                event.submissionVersion(), event.completedAt(), event.baseEventId(), event.results());
    }

    /** 明确验证错误状态，不能仅断言任何异常都算通过。 */
    private void rejects(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    /** 独立测试资源身份。 */
    private record Fixture(UUID userId, UUID bookId, LearningService.LearningItemView item) {}

    /** 核对区分分类移除和身份删除，并返回远程重置的新代际；外账户统一不可见。 */
    @Test
    void reconciliationSeparatesMembershipResetAndDeletion() {
        var f = fixture(); var foreign = fixture();
        var second = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("sync-" + UUID.randomUUID(), ""));
        learning.addDictionaryEntry(f.userId(), second.id(), f.item().dictionaryEntryId());
        var request = new LearningService.ReconcileRequest(List.of(f.item().id(), foreign.item().id()),
                List.of(f.bookId(), second.id(), foreign.bookId()));
        learning.resetWordbook(f.userId(), second.id());
        var reset = learning.reconcile(f.userId(), request);
        assertEquals(f.userId(), reset.userId()); assertEquals(1, reset.items().size());
        assertNotEquals(f.item().progressEpoch(), reset.items().getFirst().progressEpoch());
        assertEquals(List.of(foreign.item().id()), reset.missingItemIds());
        assertEquals(List.of(foreign.bookId()), reset.missingBookIds());
        learning.removeDictionaryEntry(f.userId(), f.bookId(), f.item().dictionaryEntryId());
        var retained = learning.reconcile(f.userId(), request);
        assertEquals(1, retained.items().size());
        assertTrue(retained.books().stream().filter(b -> b.book().id().equals(f.bookId())).findFirst().orElseThrow().learningItemIds().isEmpty());
        learning.deleteWordbook(f.userId(), second.id());
        var deleted = learning.reconcile(f.userId(), request);
        assertTrue(deleted.items().isEmpty()); assertTrue(deleted.missingItemIds().contains(f.item().id()));
        assertTrue(deleted.missingBookIds().contains(second.id()));
        assertEquals(1, learning.listItems(foreign.userId(), foreign.bookId()).size());
    }

    /** 核对请求有数量边界，重复身份不会多次返回，非法输入不会查询全部账户。 */
    @Test
    void reconciliationValidatesLimitsAndNormalizesDuplicates() {
        var f = fixture();
        rejects(400, () -> learning.reconcile(f.userId(), new LearningService.ReconcileRequest(null, List.of())));
        rejects(400, () -> learning.reconcile(f.userId(), new LearningService.ReconcileRequest(java.util.Arrays.asList((UUID) null), List.of())));
        rejects(400, () -> learning.reconcile(f.userId(), new LearningService.ReconcileRequest(java.util.Collections.nCopies(201, f.item().id()), List.of())));
        var snapshot = learning.reconcile(f.userId(), new LearningService.ReconcileRequest(List.of(f.item().id(), f.item().id()), List.of(f.bookId(), f.bookId())));
        assertEquals(1, snapshot.items().size()); assertEquals(1, snapshot.books().size());
    }

    /** 同一词加入两个单词本只生成一份学习条目；移除最后关联才删除进度。 */
    @Test
    void sharesLearningItemAcrossWordbooks() {
        var user = UserAccountEntity.create("learner", "learner@example.com", encoder.encode("Learner12!"),
                Set.of(Role.USER));
        accounts.saveAndFlush(user);
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "猫",
                content()), user.getId());
        var published = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试发布"), user.getId());

        var first = learning.createWordbook(user.getId(), new LearningService.CreateWordbook("日语基础", ""));
        var second = learning.createWordbook(user.getId(), new LearningService.CreateWordbook("重点词", ""));
        var itemA = learning.addDictionaryEntry(user.getId(), first.id(), published.id());
        var itemB = learning.addDictionaryEntry(user.getId(), second.id(), published.id());

        assertEquals(itemA.id(), itemB.id());
        assertEquals(1, learning.listWordbooks(user.getId()).getFirst().itemCount());
        assertEquals(1, learning.listItems(user.getId(), second.id()).size());
        assertEquals(1, learning.reviewQueue(user.getId(), first.id()).size());
        assertThrows(ResponseStatusException.class,
                () -> learning.createWordbook(user.getId(), new LearningService.CreateWordbook("日语基础", "重复")));

        var oldEpoch = itemA.progressEpoch();
        learning.resetWordbook(user.getId(), first.id());
        var reset = learning.listItems(user.getId(), second.id()).getFirst();
        assertNotEquals(oldEpoch, reset.progressEpoch());
        assertEquals(0, reset.reviewCount());
        assertFalse(reset.manualEarFocus());

        learning.removeDictionaryEntry(user.getId(), first.id(), published.id());
        assertEquals(1, learning.listItems(user.getId(), second.id()).size());
        learning.removeDictionaryEntry(user.getId(), second.id(), published.id());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_learning_item where user_id = ?", Integer.class, user.getId()));
    }

    /** 公开词条被封禁后，已有学习关联仍返回词条身份和封禁状态。 */
    @Test
    void keepsBannedIdentityVisibleToLearningList() {
        var user = UserAccountEntity.create("learner2", "learner2@example.com", encoder.encode("Learner12!"),
                Set.of(Role.USER));
        accounts.saveAndFlush(user);
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "犬",
                content()), user.getId());
        var published = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试发布"), user.getId());
        var book = learning.createWordbook(user.getId(), new LearningService.CreateWordbook("待复习", ""));
        learning.addDictionaryEntry(user.getId(), book.id(), published.id());
        dictionary.ban(published.id(), new DictionaryService.Action(published.version(), "测试封禁"), user.getId());

        var view = learning.listItems(user.getId(), book.id()).getFirst();
        assertEquals("BANNED", view.status());
        assertEquals("犬", view.written());
        assertTrue(learning.reviewQueue(user.getId(), book.id()).isEmpty());
    }

    /** 私有覆盖跨单词本共享，但另一用户和公开词典保持原样，复习基准不变。 */
    @Test
    void personalContentSharesAcrossBooksWithoutChangingPublicContentOrProgress() {
        var f = fixture();
        var publicBefore = dictionary.detail(f.item().dictionaryEntryId());
        reviews.submit(f.userId(), submission(f.item(), Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD));
        var before = learning.listItems(f.userId(), f.bookId()).getFirst();
        var saved = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "我的猫", "记忆提示", List.of(" 动物 ", "动物", "旅行", "")));
        assertEquals(1, saved.personal().revision()); assertEquals(List.of("动物", "旅行"), saved.personal().tags());
        assertEquals("我的猫", saved.entry().content().senses().getFirst().gloss());
        assertEquals(publicBefore.content().readings(), saved.entry().content().readings());
        assertEquals(publicBefore, dictionary.detail(f.item().dictionaryEntryId()));
        var second = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("shared", ""));
        var shared = learning.addDictionaryEntry(f.userId(), second.id(), f.item().dictionaryEntryId());
        assertEquals(f.item().id(), shared.id()); assertEquals(1, shared.personalContentRevision());
        var after = learning.listItems(f.userId(), f.bookId()).getFirst();
        assertEquals(before.progressEpoch(), after.progressEpoch()); assertEquals(before.fsrsState(), after.fsrsState());
        assertEquals(before.progressVersion(), after.progressVersion()); assertEquals(before.reviewCount(), after.reviewCount());
        var foreign = fixture();
        var other = learning.addDictionaryEntry(foreign.userId(), foreign.bookId(), f.item().dictionaryEntryId());
        assertEquals(publicBefore.content(), personal.detail(foreign.userId(), other.id()).entry().content());
        assertEquals(0, personal.detail(foreign.userId(), other.id()).personal().revision());
        rejects(404, () -> personal.detail(foreign.userId(), f.item().id()));
        rejects(404, () -> personal.save(foreign.userId(), f.item().id(), new PersonalContentService.Save(1L, "bad", "", List.of())));
    }

    /** 清空内容仍递增版本，防止旧表单在清空后被当作初次编辑。 */
    @Test
    void personalContentClearAndStaleFormsUseIndependentRevisions() {
        var f = fixture();
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "private", "note", List.of("tag")));
        rejects(409, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "stale", "", List.of())));
        var cleared = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(1L, null, "", List.of()));
        assertEquals(2, cleared.personal().revision()); assertNull(cleared.personal().meaningOverride());
        assertEquals(dictionary.detail(f.item().dictionaryEntryId()).content(), cleared.entry().content());
        assertEquals("", cleared.personal().notes()); assertTrue(cleared.personal().tags().isEmpty());
        rejects(409, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(1L, "stale", "", List.of())));
        assertEquals(2, jdbc.queryForObject("select count(*) from personal_entry_override_change where learning_item_id = ?", Integer.class, f.item().id()));
    }

    /** 无个人释义时始终继承最新公开版，个人释义开启时保持本人内容；封禁不泄露基准。 */
    @Test
    void personalMeaningInheritsLatestPublicationAndRespectsBan() {
        var f = fixture();
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "note", List.of()));
        var entry = dictionary.adminDetail(f.item().dictionaryEntryId());
        var changed = new DictionaryContent(1, entry.published().readings(),
                List.of(new DictionaryContent.Sense(UUID.randomUUID(), "noun", "new-public", List.of())), "手工录入", "");
        var edited = dictionary.save(entry.id(), new DictionaryService.Edit(entry.version(), changed), f.userId());
        dictionary.publish(entry.id(), new DictionaryService.Action(edited.version(), "公开更新"), f.userId());
        assertEquals("new-public", personal.detail(f.userId(), f.item().id()).entry().content().senses().getFirst().gloss());
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(1L, "private", "note", List.of()));
        var current = dictionary.adminDetail(entry.id());
        dictionary.ban(entry.id(), new DictionaryService.Action(current.version(), "封禁"), f.userId());
        var banned = personal.detail(f.userId(), f.item().id());
        assertEquals("BANNED", banned.entry().status()); assertNull(banned.entry().content());
        assertEquals("private", banned.personal().meaningOverride());
        assertTrue(learning.reviewQueue(f.userId(), f.bookId()).isEmpty());
    }

    /** 重置保留个人内容；最后关联删除后内容和审计级联消失，重新加入产生新身份。 */
    @Test
    void resetKeepsPersonalContentAndLastRemovalDeletesItPermanently() {
        var f = fixture();
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "private", "note", List.of("tag")));
        var second = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("shared", ""));
        learning.addDictionaryEntry(f.userId(), second.id(), f.item().dictionaryEntryId());
        learning.resetWordbook(f.userId(), f.bookId());
        assertEquals("note", personal.detail(f.userId(), f.item().id()).personal().notes());
        assertEquals(1, learning.listItems(f.userId(), second.id()).getFirst().personalContentRevision());
        learning.removeDictionaryEntry(f.userId(), f.bookId(), f.item().dictionaryEntryId());
        assertEquals("private", personal.detail(f.userId(), f.item().id()).personal().meaningOverride());
        learning.removeDictionaryEntry(f.userId(), second.id(), f.item().dictionaryEntryId());
        assertEquals(0, jdbc.queryForObject("select count(*) from personal_entry_override where learning_item_id = ?", Integer.class, f.item().id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from personal_entry_override_change where learning_item_id = ?", Integer.class, f.item().id()));
        var readded = learning.addDictionaryEntry(f.userId(), f.bookId(), f.item().dictionaryEntryId());
        assertNotEquals(f.item().id(), readded.id()); assertEquals(0, readded.personalContentRevision());
    }

    /** 非法表单全部拒绝，不写部分内容或审计。 */
    @Test
    void personalContentValidatesAllLimitsBeforeSaving() {
        var f = fixture();
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(null, "x", "", List.of())));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "  ", "", List.of())));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "x".repeat(4001), "", List.of())));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "x".repeat(10001), List.of())));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "", List.of("x".repeat(51)))));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "", java.util.Collections.nCopies(21, "x"))));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "", java.util.Arrays.asList((String) null))));
        assertEquals(0, personal.detail(f.userId(), f.item().id()).personal().revision());
        assertEquals(0, jdbc.queryForObject("select count(*) from personal_entry_override_change where learning_item_id = ?", Integer.class, f.item().id()));
    }

    /** 两个同基准编辑同时保存只接受一个，不静默后写覆盖。 */
    @Test
    void simultaneousPersonalEditsHaveExactlyOneWinner() throws Exception {
        var f = fixture();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready = new java.util.concurrent.CountDownLatch(2); var go = new java.util.concurrent.CountDownLatch(1);
            var tasks = List.of("one", "two").stream().map(text -> workers.submit(() -> {
                ready.countDown(); go.await();
                try { personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, text, "", List.of())); return 200; }
                catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
            })).toList();
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); go.countDown();
            var results = List.of(tasks.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS), tasks.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(results.contains(200)); assertTrue(results.contains(409));
            assertEquals(1, personal.detail(f.userId(), f.item().id()).personal().revision());
        }
    }

    /** 真实 HTTP 验证 PUT 的认证、CSRF、归属及冲突，管理员也没有他人私有内容读取权。 */
    @Test
    void personalContentHttpRequiresSessionOwnerCsrfAndVersion() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var root = "http://localhost:" + port;
        var foreign = fixture(); var path = "/api/v1/learning/items/" + foreign.item().id() + "/content";
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(root + path)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var token = httpToken(client, root);
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(PasswordTransportClient.sealForm(client, URI.create(root + "/api/v1/auth/login"), "identifier=editor&password=Editor12%21"))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        token = httpToken(client, root);
        assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(root + path)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var me = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var owner = UUID.fromString(json.readTree(me.body()).get("id").asText());
        var book = learning.createWordbook(owner, new LearningService.CreateWordbook("private-" + UUID.randomUUID(), ""));
        var item = learning.addDictionaryEntry(owner, book.id(), foreign.item().dictionaryEntryId());
        var body = json.writeValueAsString(new PersonalContentService.Save(0L, "private-http", "<script>example</script>", List.of("标签")));
        var url = URI.create(root + "/api/v1/learning/items/" + item.id() + "/content");
        assertEquals(403, client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var request = HttpRequest.newBuilder(url).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        var saved = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode()); assertEquals("private-http", json.readTree(saved.body()).get("personal").get("meaningOverride").asText());
        assertEquals(409, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("cat", dictionary.detail(item.dictionaryEntryId()).content().senses().getFirst().gloss());
        var readings = dictionary.detail(item.dictionaryEntryId()).content().readings();
        personal.save(owner, item.id(), new PersonalContentService.Save(1L, null, "", List.of(), true, readings, null));
        // 实际旧JSON不包含新增字段，不能只测由新版record序列化出的false。
        var legacyBody = "{\"expectedRevision\":2,\"meaningOverride\":null,\"notes\":\"legacy note\",\"tags\":[]}";
        var legacy = client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
            .PUT(HttpRequest.BodyPublishers.ofString(legacyBody)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, legacy.statusCode()); assertEquals(readings, personal.detail(owner, item.id()).personal().readingsOverride());
        assertEquals(1, personal.detail(owner, item.id()).personal().audioRevision());
    }

    /** 同写法可同时存在公私词条；同用户私有空间按NFKC去重，别人可拥有同写法。 */
    @Test void privateIdentityAndUniquenessAreSeparateFromPublicDictionary() {
        var f = fixture(); var foreign = fixture();
        var entry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", f.item().written(), null));
        assertNotEquals(f.item().dictionaryEntryId(), entry.id()); assertTrue(entry.content().readings().isEmpty());
        assertEquals("PRIVATE", privateEntries.reference(f.userId(), entry.id()).status());
        rejects(404, () -> dictionary.detail(entry.id()));
        var suffix = UUID.randomUUID().toString();
        privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "Ａ" + suffix, null));
        rejects(409, () -> privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "A" + suffix, null)));
        var other = privateEntries.create(foreign.userId(), new PrivateEntryService.Create("ja", "Jpan", entry.written(), null));
        assertNotEquals(entry.id(), other.id());
        rejects(404, () -> privateEntries.detail(foreign.userId(), entry.id()));
        rejects(404, () -> learning.addPrivateEntry(foreign.userId(), foreign.bookId(), entry.id()));
    }

    /** 私有词条复用FSRS、额外训练、共享重置和最后关联删除，不向基准写入内容。 */
    @Test void privateLearningSharesReviewsAndDeletesAllPrivateContentOnLastRemoval() {
        var f = fixture(); var entry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "private-" + UUID.randomUUID(), content()));
        var item = learning.addPrivateEntry(f.userId(), f.bookId(), entry.id());
        var book = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("private-shared", ""));
        assertEquals(item.id(), learning.addPrivateEntry(f.userId(), book.id(), entry.id()).id());
        assertEquals(item.id(), learning.addPrivateEntry(f.userId(), f.bookId(), entry.id()).id());
        assertNull(item.dictionaryEntryId()); assertEquals(entry.id(), item.personalCustomEntryId());
        assertTrue(learning.reviewQueue(f.userId(), f.bookId()).stream().anyMatch(row -> row.id().equals(item.id())));
        var event = submission(item, Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD);
        assertEquals("APPLIED", reviews.submit(f.userId(), event).status());
        personal.save(f.userId(), item.id(), new PersonalContentService.Save(0L, null, "私有笔记", List.of("tag")));
        learning.resetWordbook(f.userId(), book.id());
        assertEquals("私有笔记", personal.detail(f.userId(), item.id()).personal().notes());
        assertEquals(0, learning.listItems(f.userId(), book.id()).getFirst().reviewCount());
        assertEquals("cat", personal.detail(f.userId(), item.id()).entry().content().senses().getFirst().gloss());
        learning.removeLearningItem(f.userId(), f.bookId(), item.id());
        assertEquals(entry.id(), privateEntries.detail(f.userId(), entry.id()).id());
        learning.removeLearningItem(f.userId(), book.id(), item.id());
        rejects(404, () -> privateEntries.detail(f.userId(), entry.id())); rejects(404, () -> personal.detail(f.userId(), item.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from private_entry_change where entry_id = ?", Integer.class, entry.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from learning_review_event where learning_item_id = ?", Integer.class, item.id()));
        var readded = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", entry.written(), null));
        assertNotEquals(entry.id(), readded.id());
        assertEquals(0, learning.addPrivateEntry(f.userId(), f.bookId(), readded.id()).reviewCount());
    }

    /** 编辑保持身份、语言及学习基准不变，旧版本和重名均被拒绝。 */
    @Test void privateEditingChecksVersionAndDoesNotResetProgress() {
        var f = fixture(); var entry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "private-" + UUID.randomUUID(), content()));
        var item = learning.addPrivateEntry(f.userId(), f.bookId(), entry.id());
        var before = reviews.submit(f.userId(), submission(item, Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD));
        var edited = privateEntries.save(f.userId(), entry.id(), new PrivateEntryService.Edit(entry.version(), entry.written() + "改", new DictionaryContent(1, List.of(), List.of(), "", "")));
        assertEquals(entry.id(), edited.id()); assertEquals(entry.version() + 1, edited.version());
        var after = learning.listItems(f.userId(), f.bookId()).stream().filter(value -> value.id().equals(item.id())).findFirst().orElseThrow();
        assertEquals(before.fsrsState(), after.fsrsState()); assertEquals(before.progressVersion(), after.progressVersion());
        assertEquals(item.progressEpoch(), after.progressEpoch()); assertEquals(2, after.currentRevision());
        rejects(409, () -> privateEntries.save(f.userId(), entry.id(), new PrivateEntryService.Edit(entry.version(), "stale", content())));
        var duplicate = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "another-" + UUID.randomUUID(), null));
        rejects(409, () -> privateEntries.save(f.userId(), entry.id(), new PrivateEntryService.Edit(edited.version(), duplicate.written(), content())));
    }

    /** 显式删除撤销所有单词本引用，单词本本身和另一账户保留。 */
    @Test void explicitPrivateDeletionRemovesAllReferencesWithoutDeletingBooks() {
        var f = fixture(); var foreign = fixture(); var entry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "private-" + UUID.randomUUID(), content()));
        var item = learning.addPrivateEntry(f.userId(), f.bookId(), entry.id());
        var book = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("second-private", "")); learning.addPrivateEntry(f.userId(), book.id(), entry.id());
        rejects(404, () -> learning.deletePrivateEntry(foreign.userId(), entry.id()));
        assertEquals(List.of(item.id()), learning.deletePrivateEntry(f.userId(), entry.id()).deletedLearningItemIds());
        assertEquals(2, learning.listWordbooks(f.userId()).size());
        assertFalse(learning.listItems(f.userId(), f.bookId()).stream().anyMatch(value -> value.id().equals(item.id())));
        assertTrue(learning.listItems(f.userId(), book.id()).isEmpty());
        assertEquals(1, learning.listItems(foreign.userId(), foreign.bookId()).size());
    }

    /** 结构校验覆盖空字段、重复子项UUID和异常语言；分页不会读其他账户。 */
    @Test void privateContentValidationAndPaginationAreBounded() {
        var f = fixture();
        rejects(400, () -> privateEntries.create(f.userId(), new PrivateEntryService.Create(null, "Jpan", "x", null)));
        rejects(400, () -> privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "bad", "x", null)));
        rejects(400, () -> privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", " ", null)));
        var id = UUID.randomUUID();
        rejects(400, () -> privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "x", new DictionaryContent(1,
            List.of(new DictionaryContent.Reading(id, "x", "x")), List.of(new DictionaryContent.Sense(id, "", "", List.of())), "", ""))));
        for (int index = 0; index < 22; index++) privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "private-page-" + index, null));
        assertEquals(20, privateEntries.list(f.userId(), "private-page", 0).items().size());
        assertEquals(2, privateEntries.list(f.userId(), "private-page", 1).items().size());
        assertEquals(22, privateEntries.list(f.userId(), "", 0).total());
        assertEquals(0, privateEntries.list(UUID.randomUUID(), "", 0).total());
        rejects(400, () -> privateEntries.list(f.userId(), "", -1));
    }

    /** 删除单词本也遵守最后私有引用清理，不遗留没有学习身份的私人副本。 */
    @Test void deletingLastPrivateWordbookDeletesPrivateEntity() {
        var f = fixture(); var entry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "private-" + UUID.randomUUID(), null));
        learning.addPrivateEntry(f.userId(), f.bookId(), entry.id()); learning.deleteWordbook(f.userId(), f.bookId());
        rejects(404, () -> privateEntries.detail(f.userId(), entry.id()));
        assertEquals(0, privateEntries.list(f.userId(), "", 0).total());
    }

    /** 创建校验表单归属和CSRF，管理员无法访问其他账号私有词条或音频。 */
    @Test void privateHttpUsesSessionOwnerAndProtectsCreationAccount() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); var root = "http://localhost:" + port;
        var foreign = fixture(); var theirs = privateEntries.create(foreign.userId(), new PrivateEntryService.Create("ja", "Jpan", "foreign-" + UUID.randomUUID(), content()));
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/learning/private-entries")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var token = httpToken(client, root);
        client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/login")).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.ofString(PasswordTransportClient.sealForm(client, URI.create(root + "/api/v1/auth/login"), "identifier=editor&password=Editor12%21"))).build(), HttpResponse.BodyHandlers.ofString());
        token = httpToken(client, root);
        var me = client.send(HttpRequest.newBuilder(URI.create(root + "/api/v1/auth/me")).GET().build(), HttpResponse.BodyHandlers.ofString()); var owner = json.readTree(me.body()).get("id").asText();
        var body = json.writeValueAsString(new PrivateEntryService.Create("ja", "Jpan", "http-private-" + UUID.randomUUID(), null)); var url = URI.create(root + "/api/v1/learning/private-entries");
        assertEquals(403, client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json").header("X-Learning-Account", owner)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(409, client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("X-Learning-Account", foreign.userId().toString())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var response = client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json").header("X-XSRF-TOKEN", token).header("X-Learning-Account", owner)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); assertEquals(200, response.statusCode());
        assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(url + "/" + theirs.id())).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var audioUrl = root + "/api/v1/audio/entries/" + theirs.id() + "/resources/" + theirs.content().readings().getFirst().id() + "/ensure?scope=PERSONAL&kind=WORD";
        assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(audioUrl)).header("X-XSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /** 覆盖读音和例句不会修改公共内容，旧版表单保存笔记保留新结构。 */
    @Test void structuredOverridesAreOwnerOnlyAndLegacyFormsKeepThem() {
        var f = fixture(); var foreign = fixture(); var baseline = personal.detail(f.userId(), f.item().id());
        var reading = new DictionaryContent.Reading(UUID.randomUUID(), "わたし", "わたし");
        var example = new DictionaryContent.Example(UUID.randomUUID(), "私人例句", "ねこ", "自己的译文");
        var senses = List.of(new DictionaryContent.Sense(UUID.randomUUID(), "个人", "我的解释", List.of(example)));
        var saved = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "note", List.of(), true, List.of(reading), senses));
        assertEquals(List.of(reading), saved.entry().content().readings()); assertEquals(senses, saved.entry().content().senses());
        assertEquals(1, saved.personal().audioRevision());
        assertEquals(baseline.entry().content(), dictionary.detail(f.item().dictionaryEntryId()).content());
        rejects(404, () -> personal.detail(foreign.userId(), f.item().id()));
        var notes = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(1L, null, "new note", List.of("tag")));
        assertEquals(List.of(reading), notes.personal().readingsOverride()); assertEquals(senses, notes.personal().sensesOverride());
        assertEquals(1, notes.personal().audioRevision()); assertEquals(2, notes.personal().revision());
    }

    /** 空数组明确覆盖为空，null恢复继承；重置共享进度保留结构和版本。 */
    @Test void emptyStructuredOverridesAndClearingRespectSharedProgress() {
        var f = fixture(); var baseline = personal.detail(f.userId(), f.item().id());
        var otherBook = learning.createWordbook(f.userId(), new LearningService.CreateWordbook("other-" + UUID.randomUUID(), ""));
        assertEquals(f.item().id(), learning.addDictionaryEntry(f.userId(), otherBook.id(), f.item().dictionaryEntryId()).id());
        var saved = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "note", List.of(), true, List.of(), List.of()));
        assertTrue(saved.entry().content().readings().isEmpty()); assertTrue(saved.entry().content().senses().isEmpty());
        learning.resetWordbook(f.userId(), f.bookId());
        assertEquals(1, personal.detail(f.userId(), f.item().id()).personal().audioRevision());
        var cleared = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(1L, null, "", List.of(), true, null, null));
        assertEquals(baseline.entry().content(), cleared.entry().content()); assertEquals(2, cleared.personal().audioRevision());
        assertEquals(0, learning.listItems(f.userId(), otherBook.id()).getFirst().reviewCount());
        learning.removeLearningItem(f.userId(), f.bookId(), f.item().id());
        assertEquals(2, personal.detail(f.userId(), f.item().id()).personal().audioRevision());
        learning.removeLearningItem(f.userId(), otherBook.id(), f.item().id()); rejects(404, () -> personal.detail(f.userId(), f.item().id()));
    }

    /** 未覆盖字段继续跟随发布内容，音频版本只在结构本身变化时增加。 */
    @Test void readingOnlyOverrideInheritsSensesAndDoesNotChangeFsrsBaseline() {
        var f = fixture(); var reading = new DictionaryContent.Reading(UUID.randomUUID(), "个人", "ねこ");
        var before = reviews.submit(f.userId(), submission(f.item(), Instant.parse("2026-09-30T12:00:00Z"), "0", null, Rating.GOOD));
        personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "simple", "", List.of(), true, List.of(reading), null));
        var current = dictionary.adminDetail(f.item().dictionaryEntryId()); var next = content();
        var draft = dictionary.save(current.id(), new DictionaryService.Edit(current.version(), next), f.userId());
        dictionary.publish(current.id(), new DictionaryService.Action(draft.version(), "next"), f.userId());
        var saved = personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(1L, null, "", List.of(), true, List.of(reading), null));
        assertEquals(next.senses(), saved.entry().content().senses()); assertEquals(List.of(reading), saved.entry().content().readings());
        assertEquals(1, saved.personal().audioRevision());
        var item = learning.listItems(f.userId(), f.bookId()).getFirst(); assertEquals(before.fsrsState(), item.fsrsState());
        assertEquals(before.progressVersion(), item.progressVersion()); assertEquals(1, item.personalAudioRevision());
    }

    /** 限制和重复UUID同公共内容，互斥释义及私人词条的重复覆盖入口明确拒绝。 */
    @Test void structuredOverridesValidateLimitsAndEntryKind() {
        var f = fixture(); var id = UUID.randomUUID();
        var duplicate = List.of(new DictionaryContent.Reading(id, "a", "a"), new DictionaryContent.Reading(id, "b", "b"));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "", List.of(), true, duplicate, null)));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, "simple", "", List.of(), true, null, List.of())));
        rejects(400, () -> personal.save(f.userId(), f.item().id(), new PersonalContentService.Save(0L, null, "", List.of(), true,
            List.of(new DictionaryContent.Reading(id, "x", "x".repeat(501))), null)));
        assertEquals(0, personal.detail(f.userId(), f.item().id()).personal().revision());
        var entry = privateEntries.create(f.userId(), new PrivateEntryService.Create("ja", "Jpan", "private-" + UUID.randomUUID(), null));
        var item = learning.addPrivateEntry(f.userId(), f.bookId(), entry.id());
        rejects(400, () -> personal.save(f.userId(), item.id(), new PersonalContentService.Save(0L, null, "", List.of(), true, List.of(), null)));
    }

    /** 最小可发布内容，覆盖词典与学习条目之间的真实外键路径。 */
    private DictionaryContent content() {
        return new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ")),
                List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", "cat", List.of())),
                "手工录入", "");
    }
}
