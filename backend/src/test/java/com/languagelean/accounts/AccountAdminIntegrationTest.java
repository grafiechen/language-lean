package com.languagelean.accounts;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import com.languagelean.learning.*;
import com.languagelean.dictionary.*;
import com.languagelean.audio.*;
import com.languagelean.sync.ReviewSubmission;
import com.languagelean.reviews.domain.Rating;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 HTTP、会话、CSRF 和 JPA 事务测试；邮件由测试替身捕获，不发送外部邮件。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:accountadmin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql",
    "app.bootstrap-admin.username=owner", "app.bootstrap-admin.email=owner@example.test",
    "app.bootstrap-admin.password=Preview12!", "app.mail.public-url=https://learning.example.test", "app.audio.cleanup-enabled=false", "app.audio.auto-save-enabled=false"
})
@Import({AccountAdminIntegrationTest.MailConfiguration.class, AudioTestDoubles.class})
class AccountAdminIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired UserLoginIdentifierRepository identifiers;
    @Autowired AccountPasswordResetRepository resets;
    @Autowired AccountAdminAuditRepository audit;
    @Autowired PasswordEncoder encoder;
    @Autowired FakeMail mail;
    @Autowired LearningService learning;
    @Autowired PrivateEntryService privateEntries;
    @Autowired PersonalContentService personal;
    @Autowired DictionaryService dictionary;
    @Autowired ContributionService contributions;
    @Autowired AudioService audio;
    @Autowired AudioCleanupService cleanup;
    @Autowired AudioTestDoubles.TestSpeech speech;
    @Autowired AudioTestDoubles.TestObjects objects;
    @Autowired org.springframework.jdbc.core.JdbcTemplate sql;

    @Test void selfClosurePurgesAllPrivateDataAndKeepsIndependentPublishedData() throws Exception {
        objects.reset(); speech.reset(); var admin = login("owner", "Preview12!");
        var name = unique(); var owner = UUID.fromString(create(admin, name).path("id").asText());
        var otherName = unique(); var other = UUID.fromString(create(admin, otherName).path("id").asText());
        var user = login(name, mail.passwords.get(name + "@example.test"));
        var oldSession = login(name, mail.passwords.get(name + "@example.test"));
        var source = privateEntries.create(owner, new PrivateEntryService.Create("ja", "Jpan", "公開" + unique(), closureContent()));
        var pending = privateEntries.create(owner, new PrivateEntryService.Create("ja", "Jpan", "非公開" + unique(), closureContent()));
        var approved = contributions.submit(owner, closureSubmission(source));
        var published = contributions.approve(accounts.findAll().stream().filter(a -> a.getUsername().equals("owner")).findFirst().orElseThrow().getId(), approved.row().id(), new ContributionService.Action(approved.version(), ""));
        contributions.submit(owner, closureSubmission(pending));
        var publicId = published.row().publishedEntryId(); var publicBefore = dictionary.detail(publicId);
        var book = learning.createWordbook(owner, new LearningService.CreateWordbook("closure", ""));
        var item = learning.addDictionaryEntry(owner, book.id(), publicId); learning.addPrivateEntry(owner, book.id(), source.id());
        personal.save(owner, item.id(), new PersonalContentService.Save(0L, "本人释义", "私密笔记", List.of("私人标签")));
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(200, post(user, "/api/v1/learning/reviews", new ReviewSubmission(UUID.randomUUID(), UUID.randomUUID(), item.id(), item.progressEpoch(), item.progressVersion(), now.toString(), now, null,
                List.of(new ReviewSubmission.TypeResult("LISTEN_RECALL", 1, List.of(new ReviewSubmission.Trial(UUID.randomUUID(), now, Rating.GOOD)))))).statusCode());
        post(client(), "/api/v1/auth/password-recovery", Map.of("identifier", name));
        var privateClip = audio.ensureResource(source.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, source.content().readings().getFirst().id(), owner, false, false);
        var newerPrivateClip = audio.ensureResource(source.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, source.content().readings().getFirst().id(), owner, false, true);
        var overrideReading = new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ");
        personal.save(owner, item.id(), new PersonalContentService.Save(1L, "本人释义", "私密笔记", List.of("私人标签"), true, List.of(overrideReading), null));
        var overrideClip = audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, overrideReading.id(), owner, false, false);
        var publicClip = audio.ensureResource(publicId, AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, publicBefore.content().readings().getFirst().id(), other, false, false);
        var foreign = privateEntries.create(other, new PrivateEntryService.Create("ja", "Jpan", "他人" + unique(), closureContent()));
        var otherClip = audio.ensureResource(foreign.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, foreign.content().readings().getFirst().id(), other, false, false);
        var privateKey = sql.queryForObject("select object_key from audio_version where id=?", String.class, privateClip.audioVersionId());
        var newerKey = sql.queryForObject("select object_key from audio_version where id=?", String.class, newerPrivateClip.audioVersionId());
        var overrideKey = sql.queryForObject("select object_key from audio_version where id=?", String.class, overrideClip.audioVersionId());
        assertEquals(200, post(user, "/api/v1/auth/close-account", Map.of("accountId", owner, "password", mail.passwords.get(name + "@example.test"), "confirmation", name)).statusCode());
        assertFalse(accounts.existsById(owner)); assertFalse(identifiers.existsById(name)); assertFalse(identifiers.existsById(name + "@example.test"));
        for (var table : new String[]{"wordbook", "user_learning_item", "learning_review_event", "personal_custom_entry", "user_account_role", "user_login_identifier"})
            assertEquals(0L, sql.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, owner));
        assertEquals(0L, sql.queryForObject("select count(*) from dictionary_contribution_submission where submitted_by=?", Long.class, owner));
        assertTrue(resets.findFirstByAccountIdOrderByCreatedAtDesc(owner).isEmpty());
        assertEquals(0L, sql.queryForObject("select count(*) from audio_version where id=?", Long.class, privateClip.audioVersionId()));
        assertEquals(0L, sql.queryForObject("select count(*) from audio_version where id in (?,?)", Long.class, newerPrivateClip.audioVersionId(), overrideClip.audioVersionId()));
        assertEquals(0L, sql.queryForObject("select count(*) from personal_entry_override where learning_item_id=?", Long.class, item.id()));
        assertEquals(publicBefore, dictionary.detail(publicId));
        var credit = contributions.credits(publicId).getFirst(); assertEquals("CC0", credit.license()); assertEquals("测试来源", credit.sourceName()); assertTrue(credit.contributorDeleted());
        assertEquals(0L, sql.queryForObject("select count(*) from dictionary_revision where contributed_by=?", Long.class, owner));
        assertTrue(privateEntries.detail(other, foreign.id()) != null); assertTrue(audio.content(otherClip.audioVersionId(), other, false).length > 0); assertTrue(audio.content(publicClip.audioVersionId(), other, false).length > 0);
        objects.fail = true; assertEquals(0, cleanup.drain()); assertTrue(objects.files.containsKey(privateKey));
        objects.fail = false; assertTrue(cleanup.drain() > 0); assertFalse(objects.files.containsKey(privateKey));
        assertFalse(objects.files.containsKey(newerKey)); assertFalse(objects.files.containsKey(overrideKey));
        assertTrue(audio.content(otherClip.audioVersionId(), other, false).length > 0); assertTrue(audio.content(publicClip.audioVersionId(), other, false).length > 0);
        var revoked = get(oldSession, "/api/v1/auth/me"); assertEquals(401, revoked.statusCode()); assertTrue(revoked.body().contains("ACCOUNT_DELETED"));
    }

    @Test void closureRequiresExactIdentityPasswordConfirmationAndKeepsLastAdministrator() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); var owner = UUID.fromString(create(admin, name).path("id").asText());
        var user = login(name, mail.passwords.get(name + "@example.test"));
        assertEquals(409, post(user, "/api/v1/auth/close-account", Map.of("accountId", UUID.randomUUID(), "password", mail.passwords.get(name + "@example.test"), "confirmation", name)).statusCode());
        assertEquals(400, post(user, "/api/v1/auth/close-account", Map.of("accountId", owner, "password", "Wrong12!", "confirmation", name)).statusCode());
        assertEquals(400, post(user, "/api/v1/auth/close-account", Map.of("accountId", owner, "password", mail.passwords.get(name + "@example.test"), "confirmation", "wrong")).statusCode());
        assertTrue(accounts.existsById(owner));
        var actor = json.readTree(get(admin, "/api/v1/auth/me").body()).path("id").asText();
        assertEquals(400, post(admin, "/api/v1/auth/close-account", Map.of("accountId", actor, "password", "Preview12!", "confirmation", "owner")).statusCode());
        assertEquals(403, post(user, "/api/v1/admin/accounts/" + actor + "/delete", Map.of("actorId", owner, "version", 0, "password", mail.passwords.get(name + "@example.test"), "confirmation", "owner")).statusCode());
        assertEquals(200, get(admin, "/api/v1/auth/me").statusCode());
    }

    @Test void adminDeletionIsVersionedAndRecreationCannotClaimOldIdentityOrProgress() throws Exception {
        var admin = login("owner", "Preview12!"); var actor = json.readTree(get(admin, "/api/v1/auth/me").body()).path("id").asText();
        var name = unique(); var created = create(admin, name); var id = UUID.fromString(created.path("id").asText()); var user = login(name, mail.passwords.get(name + "@example.test"));
        var book = learning.createWordbook(id, new LearningService.CreateWordbook("old", ""));
        post(user, "/api/v1/auth/preferences", Map.of("nativeLanguage", "en"));
        var old = Map.of("actorId", actor, "version", created.path("version").asLong(), "password", "Preview12!", "confirmation", name);
        assertEquals(409, post(admin, "/api/v1/admin/accounts/" + id + "/delete", old).statusCode());
        var body = Map.of("actorId", actor, "version", accounts.findById(id).orElseThrow().getVersion(), "password", "Preview12!", "confirmation", name);
        assertEquals(200, post(admin, "/api/v1/admin/accounts/" + id + "/delete", body).statusCode());
        assertEquals(200, get(admin, "/api/v1/auth/me").statusCode()); assertEquals(401, get(user, "/api/v1/auth/me").statusCode());
        var replacement = create(admin, name); assertNotEquals(id.toString(), replacement.path("id").asText());
        // 重放删除使用旧 UUID，即便用户名已复用也不能删除新账号。
        assertEquals(200, post(admin, "/api/v1/admin/accounts/" + id + "/delete", body).statusCode());
        var current = login(name, mail.passwords.get(name + "@example.test")); assertEquals("[]", get(current, "/api/v1/learning/wordbooks").body());
        assertEquals(404, get(current, "/api/v1/learning/wordbooks/" + book.id() + "/items").statusCode());
    }

    @Test void latePersonalAudioUploadAfterClosureBecomesCleanupInsteadOfLostPrivateFile() throws Exception {
        speech.reset(); var admin = login("owner", "Preview12!"); var name = unique(); var id = UUID.fromString(create(admin, name).path("id").asText());
        var source = privateEntries.create(id, new PrivateEntryService.Create("ja", "Jpan", "遅延" + unique(), closureContent()));
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        speech.callback = text -> { started.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); } catch (InterruptedException e) { throw new RuntimeException(e); } };
        try {
            var running = CompletableFuture.supplyAsync(() -> audio.ensureResource(source.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, source.content().readings().getFirst().id(), id, false, true));
            assertTrue(started.await(5, TimeUnit.SECONDS)); var user = login(name, mail.passwords.get(name + "@example.test"));
            assertEquals(200, post(user, "/api/v1/auth/close-account", Map.of("accountId", id, "password", mail.passwords.get(name + "@example.test"), "confirmation", name)).statusCode());
            release.countDown(); assertNotEquals(AudioService.Status.READY, running.get(5, TimeUnit.SECONDS).status());
            assertTrue(sql.queryForObject("select count(*) from audio_object_cleanup", Long.class) > 0); assertTrue(cleanup.drain() > 0);
        } finally { release.countDown(); speech.callback = null; }
    }

    private DictionaryContent closureContent() { return new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ")), List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", "测试释义", List.of())), "测试来源", "CC0"); }
    private ContributionService.Submit closureSubmission(PrivateEntryService.View source) { return new ContributionService.Submit(UUID.randomUUID(), source.id(), source.version(), null, null, null, source.content(), "", true); }

    @Test void recoveryCheckIsReadOnlyScopedAndReportsAcceptedResetAndDeletedIdentities() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); var owner = UUID.fromString(create(admin, name).path("id").asText());
        var user = login(name, mail.passwords.get(name + "@example.test"));
        var foreignName = unique(); var foreign = UUID.fromString(create(admin, foreignName).path("id").asText());
        var book = learning.createWordbook(owner, new LearningService.CreateWordbook("恢复核对", ""));
        var entry = privateEntries.create(owner, new PrivateEntryService.Create("ja", "Jpan", "復元" + unique(), closureContent()));
        var item = learning.addPrivateEntry(owner, book.id(), entry.id());
        var foreignBook = learning.createWordbook(foreign, new LearningService.CreateWordbook("不可读取", ""));
        var foreignEntry = privateEntries.create(foreign, new PrivateEntryService.Create("ja", "Jpan", "秘密" + unique(), closureContent()));
        var foreignItem = learning.addPrivateEntry(foreign, foreignBook.id(), foreignEntry.id());
        var completed = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var event = new ReviewSubmission(UUID.randomUUID(), UUID.randomUUID(), item.id(), item.progressEpoch(), "0", completed.toString(), completed, null,
                List.of(new ReviewSubmission.TypeResult("LISTEN_RECALL", 1, List.of(new ReviewSubmission.Trial(UUID.randomUUID(), completed, Rating.HARD)))));
        assertEquals(200, post(user, "/api/v1/learning/reviews", event).statusCode());
        var foreignEvent = new ReviewSubmission(UUID.randomUUID(), UUID.randomUUID(), foreignItem.id(), foreignItem.progressEpoch(), "0", completed.toString(), completed, null, event.results());
        var foreignSession = login(foreignName, mail.passwords.get(foreignName + "@example.test"));
        assertEquals(200, post(foreignSession, "/api/v1/learning/reviews", foreignEvent).statusCode());
        var unknown = UUID.randomUUID();
        var request = Map.of("accountId", owner, "learningItemIds", List.of(item.id(), foreignItem.id()), "wordbookIds", List.of(book.id(), foreignBook.id()), "eventIds", List.of(event.eventId(), foreignEvent.eventId(), unknown));
        var before = learning.listItems(owner, book.id());
        var response = post(user, "/api/v1/learning/recovery-check", request); assertEquals(200, response.statusCode(), response.body());
        var checked = json.readTree(response.body()); assertEquals(owner.toString(), checked.path("state").path("userId").asText());
        assertEquals(1, checked.path("state").path("items").size()); assertEquals(foreignItem.id().toString(), checked.path("state").path("missingItemIds").get(0).asText());
        assertEquals("ACCEPTED", checked.path("events").get(0).path("status").asText());
        assertEquals(event.eventId().toString(), checked.path("events").get(0).path("submission").path("eventId").asText());
        assertEquals("CONFLICT", checked.path("events").get(1).path("status").asText()); assertTrue(checked.path("events").get(1).path("submission").isNull());
        assertEquals("MISSING", checked.path("events").get(2).path("status").asText());
        assertEquals(json.writeValueAsString(before), json.writeValueAsString(learning.listItems(owner, book.id())));
        learning.resetWordbook(owner, book.id());
        var reset = json.readTree(post(user, "/api/v1/learning/recovery-check", request).body());
        assertNotEquals(item.progressEpoch().toString(), reset.path("state").path("items").get(0).path("progressEpoch").asText());
        assertEquals("MISSING", reset.path("events").get(0).path("status").asText());
        learning.deleteWordbook(owner, book.id());
        var deleted = json.readTree(post(user, "/api/v1/learning/recovery-check", request).body());
        assertEquals(0, deleted.path("state").path("items").size()); assertEquals(2, deleted.path("state").path("missingBookIds").size());
    }

    @Test void recoveryCheckBindsAccountLimitsBatchAndRequiresCsrf() throws Exception {
        var admin = login("owner", "Preview12!"); var actor = json.readTree(get(admin, "/api/v1/auth/me").body()).path("id").asText();
        var empty = Map.of("accountId", actor, "learningItemIds", List.of(), "wordbookIds", List.of(), "eventIds", List.of());
        assertEquals(200, post(admin, "/api/v1/learning/recovery-check", empty).statusCode());
        assertEquals(409, post(admin, "/api/v1/learning/recovery-check", Map.of("accountId", UUID.randomUUID(), "learningItemIds", List.of(), "wordbookIds", List.of(), "eventIds", List.of())).statusCode());
        assertEquals(400, post(admin, "/api/v1/learning/recovery-check", Map.of("accountId", actor, "learningItemIds", List.of(), "wordbookIds", List.of(), "eventIds", java.util.stream.IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID()).toList())).statusCode());
        assertEquals(403, admin.send(HttpRequest.newBuilder(uri("/api/v1/learning/recovery-check")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(empty))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void createsSaltedAccountWithoutExposingCredentialsAndRestrictsAdminApis() throws Exception {
        var admin = login("owner", "Preview12!");
        var name = unique(); var user = create(admin, name);
        var stored = accounts.findById(UUID.fromString(user.path("id").asText())).orElseThrow();
        var password = mail.passwords.get(name + "@example.test");
        assertTrue(PasswordPolicy.isValid(password)); assertTrue(stored.getPasswordHash().startsWith("{bcrypt}"));
        assertTrue(encoder.matches(password, stored.getPasswordHash())); assertNotEquals(password, stored.getPasswordHash());
        assertTrue(user.path("mustChangePassword").asBoolean());
        assertFalse(user.toString().contains(password)); assertFalse(user.toString().contains("passwordHash"));
        var session = login(name.toUpperCase(), password);
        assertEquals(403, get(session, "/api/v1/admin/accounts").statusCode());
        assertEquals(403, post(session, "/api/v1/admin/accounts", Map.of("username", unique(), "email", "other@example.test")).statusCode());
        var other = login((name + "@example.test").toUpperCase(), password);
        assertEquals(stored.getId().toString(), json.readTree(get(other, "/api/v1/auth/me").body()).path("id").asText());
        var results = json.readTree(get(admin, "/api/v1/admin/accounts?q=" + name.toUpperCase()).body());
        assertEquals(1, results.path("total").asInt());
        assertFalse(results.toString().contains("passwordHash")); assertFalse(results.toString().contains("learning"));
        assertEquals(0, json.readTree(get(admin, "/api/v1/admin/accounts?q=%25").body()).path("total").asInt());
    }

    @Test void rejectsDuplicatesAcrossUsernameAndEmailAndRollsBackFailedMail() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); create(admin, name);
        int mails = mail.passwords.size();
        assertEquals(409, post(admin, "/api/v1/admin/accounts", Map.of("username", name.toUpperCase(), "email", unique() + "@example.test")).statusCode());
        assertEquals(409, post(admin, "/api/v1/admin/accounts", Map.of("username", name + "@example.test", "email", unique() + "@example.test")).statusCode());
        assertEquals(400, post(admin, "/api/v1/admin/accounts", Map.of("username", "same@example.test", "email", "same@example.test")).statusCode());
        for (var email : new String[]{"not-an-email", "A <a@example.test>", "a@example.test,b@example.test", "Group:a@example.test,b@example.test;", "a@example.test\r\nBcc: b@example.test"})
            assertEquals(400, post(admin, "/api/v1/admin/accounts", Map.of("username", unique(), "email", email)).statusCode());
        assertEquals(mails, mail.passwords.size());
        var failName = "fail-" + unique(); long count = accounts.count(); long audits = audit.count();
        assertEquals(503, post(admin, "/api/v1/admin/accounts", Map.of("username", failName, "email", "fail-" + failName + "@example.test")).statusCode());
        assertEquals(count, accounts.count()); assertEquals(audits, audit.count());
        assertFalse(identifiers.existsById(failName)); assertFalse(identifiers.existsById("fail-" + failName + "@example.test"));
    }

    @Test void disablingInvalidatesSessionsPermanentlyAndUsesOptimisticVersion() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); var created = create(admin, name);
        var session = login(name, mail.passwords.get(name + "@example.test"));
        var id = created.path("id").asText(); long version = created.path("version").asLong();
        var disabled = post(admin, "/api/v1/admin/accounts/" + id + "/status", Map.of("version", version, "status", "DISABLED"));
        assertEquals(200, disabled.statusCode());
        assertEquals(401, loginResponse(client(), name, mail.passwords.get(name + "@example.test")).statusCode());
        assertEquals(409, post(admin, "/api/v1/admin/accounts/" + id + "/status", Map.of("version", version, "status", "ACTIVE")).statusCode());
        long current = json.readTree(disabled.body()).path("version").asLong();
        assertEquals(200, post(admin, "/api/v1/admin/accounts/" + id + "/status", Map.of("version", current, "status", "ACTIVE")).statusCode());
        // 即使旧会话从未在禁用期间发起请求，重新启用后也不能恢复权限。
        assertEquals(401, get(session, "/api/v1/learning/wordbooks").statusCode());
        assertEquals(200, get(login(name, mail.passwords.get(name + "@example.test")), "/api/v1/auth/me").statusCode());
        var owner = json.readTree(get(admin, "/api/v1/admin/accounts?q=owner").body()).path("items").get(0);
        assertEquals(400, post(admin, "/api/v1/admin/accounts/" + owner.path("id").asText() + "/status",
                Map.of("version", owner.path("version").asLong(), "status", "DISABLED")).statusCode());
    }

    @Test void resetLinksAreHashedSingleUseAndRevokeOldSessions() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); var created = create(admin, name);
        var old = login(name, mail.passwords.get(name + "@example.test")); var anonymous = client();
        var known = post(anonymous, "/api/v1/auth/password-recovery", Map.of("identifier", name));
        var unknown = post(anonymous, "/api/v1/auth/password-recovery", Map.of("identifier", unique()));
        assertEquals(202, known.statusCode()); assertEquals(known.body(), unknown.body());
        var link = mail.links.get(name + "@example.test"); assertTrue(link.startsWith("https://learning.example.test/reset-password#token="));
        var token = link.substring(link.indexOf("#token=") + 7);
        assertTrue(resets.existsById(AccountSecrets.digest(token))); assertFalse(resets.existsById(token));
        assertEquals(400, post(anonymous, "/api/v1/auth/password-reset", Map.of("token", token, "password", "short1!", "confirmation", "short1!")).statusCode());
        var request = Map.of("token", token, "password", "Newpass8!", "confirmation", "Newpass8!");
        assertEquals(200, post(anonymous, "/api/v1/auth/password-reset", request).statusCode());
        assertEquals(400, post(anonymous, "/api/v1/auth/password-reset", request).statusCode());
        assertFalse(resets.existsById(AccountSecrets.digest(token)));
        assertEquals(401, get(old, "/api/v1/auth/me").statusCode());
        assertEquals(401, loginResponse(client(), name, mail.passwords.get(name + "@example.test")).statusCode());
        var current = login(name, "Newpass8!"); assertEquals(created.path("id").asText(), json.readTree(get(current, "/api/v1/auth/me").body()).path("id").asText());
    }

    @Test void expiredLinksDisabledAccountsAndChangedPasswordsCannotReset() throws Exception {
        var admin = login("owner", "Preview12!"); var anonymous = client(); var name = unique(); var created = create(admin, name);
        var id = UUID.fromString(created.path("id").asText()); var account = accounts.findById(id).orElseThrow();
        var expiredToken = AccountSecrets.token(); resets.save(new AccountPasswordReset(expiredToken, account, Instant.now().minusSeconds(1801)));
        assertEquals(400, post(anonymous, "/api/v1/auth/password-reset", Map.of("token", expiredToken, "password", "Newpass8!", "confirmation", "Newpass8!")).statusCode());
        assertEquals(202, post(anonymous, "/api/v1/auth/password-recovery", Map.of("identifier", name)).statusCode());
        var token = mail.links.get(name + "@example.test").split("#token=")[1];
        var user = login(name, mail.passwords.get(name + "@example.test"));
        assertEquals(200, post(user, "/api/v1/auth/password", Map.of("currentPassword", mail.passwords.get(name + "@example.test"), "newPassword", "Changed8!", "confirmation", "Changed8!")).statusCode());
        assertEquals(200, get(user, "/api/v1/auth/me").statusCode());
        assertEquals(400, post(anonymous, "/api/v1/auth/password-reset", Map.of("token", token, "password", "Newpass8!", "confirmation", "Newpass8!")).statusCode());
        account = accounts.findById(id).orElseThrow(); var token2 = AccountSecrets.token(); resets.deleteByAccountId(id);
        resets.save(new AccountPasswordReset(token2, account, Instant.now()));
        assertEquals(200, post(admin, "/api/v1/admin/accounts/" + id + "/status", Map.of("version", account.getVersion(), "status", "DISABLED")).statusCode());
        assertEquals(400, post(anonymous, "/api/v1/auth/password-reset", Map.of("token", token2, "password", "Newpass8!", "confirmation", "Newpass8!")).statusCode());
    }

    @Test void resetRequestIsRateLimitedAndFailedMailLeavesNoToken() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); create(admin, name); var anon = client();
        int before = mail.resetCount;
        assertEquals(202, post(anon, "/api/v1/auth/password-recovery", Map.of("identifier", name)).statusCode());
        assertEquals(202, post(anon, "/api/v1/auth/password-recovery", Map.of("identifier", name)).statusCode());
        assertEquals(before + 1, mail.resetCount);
        // 使用直接存储的测试账号验证邮件失败，不能通过管理员新增一个没有初始密码的账号。
        var fail = accounts.saveAndFlush(UserAccountEntity.create("fail" + unique(), "fail-recovery@example.test", encoder.encode("Preview12!"), java.util.Set.of(Role.USER)));
        assertEquals(202, post(anon, "/api/v1/auth/password-recovery", Map.of("identifier", fail.getUsername())).statusCode());
        assertTrue(resets.findFirstByAccountIdOrderByCreatedAtDesc(fail.getId()).isEmpty());
    }

    @Test void csrfIsRequiredEvenForPublicPasswordResetAndAnonymousCannotManageAccounts() throws Exception {
        var anonymous = client();
        assertEquals(401, get(anonymous, "/api/v1/admin/accounts").statusCode());
        for (var path : new String[]{"/api/v1/admin/accounts", "/api/v1/auth/password-recovery", "/api/v1/auth/password-reset"})
            assertEquals(403, anonymous.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    /** 普通用户不能处理他人反馈，陈旧账号表单和缺少CSRF被拒绝，反馈不会触发TTS。 */
    @Test void feedbackHttpAuthorizesOwnerAndAdministrator() throws Exception {
        var admin = login("owner", "Preview12!"); var name = unique(); var userId = UUID.fromString(create(admin, name).path("id").asText());
        var user = login(name, mail.passwords.get(name + "@example.test"));
        var adminId = accounts.findAll().stream().filter(a -> a.getUsername().equals("owner")).findFirst().orElseThrow().getId();
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "反馈" + unique(), closureContent()), adminId);
        var entry = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试"), adminId);
        var reading = entry.published().readings().getFirst();
        var request = new AudioFeedbackService.Submit(UUID.randomUUID(), entry.id(), reading.id(), AudioService.Kind.WORD, "WRONG_PRONUNCIATION", reading.pronunciationText(), null, "读音错误");
        assertEquals(401, get(client(), "/api/v1/audio-feedback").statusCode());
        assertEquals(403, get(user, "/api/v1/admin/audio-feedback").statusCode());
        assertEquals(409, postAs(user, "/api/v1/audio-feedback", request, adminId).statusCode());
        assertEquals(403, user.send(HttpRequest.newBuilder(uri("/api/v1/audio-feedback")).header("Content-Type", "application/json").header("X-Learning-Account", userId.toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var response = postAs(user, "/api/v1/audio-feedback", request, userId); assertEquals(200, response.statusCode(), response.body());
        assertEquals(request.id().toString(), json.readTree(postAs(user, "/api/v1/audio-feedback", request, userId).body()).path("id").asText());
        assertEquals(1, json.readTree(get(user, "/api/v1/audio-feedback").body()).path("total").asInt());
        assertEquals(0, json.readTree(get(admin, "/api/v1/audio-feedback?entryId=" + entry.id()).body()).path("total").asInt());
        var path = "/api/v1/admin/audio-feedback/" + request.id();
        assertEquals(403, get(user, path).statusCode()); assertEquals(200, get(admin, path).statusCode());
        assertEquals(409, postAs(admin, path + "/regenerate", Map.of("version", 0), userId).statusCode());
        assertEquals(409, postAs(admin, path + "/resolve", new AudioFeedbackService.Action(0, "RESOLVED", "尚未生成"), adminId).statusCode());
        assertEquals(200, postAs(admin, path + "/resolve", new AudioFeedbackService.Action(0, "DISMISSED", "经核对读音正确"), adminId).statusCode());
        var mine = get(user, "/api/v1/audio-feedback").body(); assertTrue(mine.contains("经核对读音正确")); assertFalse(mine.contains("objectKey")); assertFalse(mine.contains(name + "@example.test"));
    }

    /** 注销删除自愿提交的私人说明，另一用户反馈和公共词典仍然保留。 */
    @Test void closureDeletesOnlyOwnAudioFeedback() throws Exception {
        var admin = login("owner", "Preview12!"); var adminId = accounts.findAll().stream().filter(a -> a.getUsername().equals("owner")).findFirst().orElseThrow().getId();
        var name = unique(); var userId = UUID.fromString(create(admin, name).path("id").asText()); var password = mail.passwords.get(name + "@example.test"); var user = login(name, password);
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "反馈注销" + unique(), closureContent()), adminId);
        var entry = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试"), adminId); var reading = entry.published().readings().getFirst();
        var own = new AudioFeedbackService.Submit(UUID.randomUUID(), entry.id(), reading.id(), AudioService.Kind.WORD, "UNPLAYABLE", reading.pronunciationText(), null, "个人反馈说明");
        var other = new AudioFeedbackService.Submit(UUID.randomUUID(), entry.id(), reading.id(), AudioService.Kind.WORD, "UNPLAYABLE", reading.pronunciationText(), null, "另一用户反馈");
        assertEquals(200, postAs(user, "/api/v1/audio-feedback", own, userId).statusCode()); assertEquals(200, postAs(admin, "/api/v1/audio-feedback", other, adminId).statusCode());
        assertEquals(200, post(user, "/api/v1/auth/close-account", Map.of("accountId", userId, "password", password, "confirmation", name)).statusCode());
        assertEquals(404, get(admin, "/api/v1/admin/audio-feedback/" + own.id()).statusCode()); assertEquals(200, get(admin, "/api/v1/admin/audio-feedback/" + other.id()).statusCode());
        assertEquals("PUBLISHED", dictionary.detail(entry.id()).status());
    }

    /** 测试表单显式绑定用户UUID，Cookie变化不能更改提交署名。 */
    private HttpResponse<String> postAs(HttpClient c, String path, Object body, UUID actor) throws Exception {
        var token = json.readTree(get(c, "/api/v1/auth/csrf").body());
        return c.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-Learning-Account", actor.toString())
                .header(token.path("headerName").asText(), token.path("token").asText()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode create(HttpClient admin, String name) throws Exception {
        var response = post(admin, "/api/v1/admin/accounts", Map.of("username", name, "email", name + "@example.test"));
        assertEquals(200, response.statusCode(), response.body()); return json.readTree(response.body());
    }
    private HttpClient login(String name, String password) throws Exception { var c = client(); assertEquals(200, loginResponse(c, name, password).statusCode()); return c; }
    private HttpResponse<String> loginResponse(HttpClient c, String name, String password) throws Exception {
        var token = json.readTree(get(c, "/api/v1/auth/csrf").body());
        return c.send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .header(token.path("headerName").asText(), token.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString("identifier=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(HttpClient c, String path, Object body) throws Exception {
        var token = json.readTree(get(c, "/api/v1/auth/csrf").body());
        return c.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header(token.path("headerName").asText(), token.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(HttpClient c, String path) throws Exception { return c.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    private String unique() { return "user-" + UUID.randomUUID().toString().substring(0, 12); }

    @TestConfiguration static class MailConfiguration { @Bean @Primary FakeMail accountTestMail() { return new FakeMail(); } }
    /** 仅测试替身短暂保留发送内容，不写日志或持久化文件。 */
    static class FakeMail implements AccountMailPort {
        final Map<String, String> passwords = new ConcurrentHashMap<>(), links = new ConcurrentHashMap<>(); int resetCount;
        public boolean configured() { return true; }
        public void sendInitialPassword(String email, String username, String password) { reject(email); passwords.put(email, password); }
        public void sendPasswordReset(String email, String username, String link) { reject(email); links.put(email, link); resetCount++; }
        private void reject(String email) { if (email.startsWith("fail-")) throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "邮件发送失败"); }
    }
}
