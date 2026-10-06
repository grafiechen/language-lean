package com.languagelean.accounts;

import com.languagelean.audio.*;
import com.languagelean.dictionary.*;
import com.languagelean.learning.PrivateEntryService;
import com.languagelean.learning.LearningService;
import com.languagelean.learning.PersonalContentService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 ORM 事务验证生成锁、云失败、权限隔离及音频版本生命周期。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:audio;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql,classpath:db/migration/V20__password_transport_keys.sql,classpath:db/migration/V21__audio_generation_usage.sql,classpath:db/migration/V22__database_backups.sql",
    "app.bootstrap-admin.username=audio-editor", "app.bootstrap-admin.email=audio@example.test", "app.bootstrap-admin.password=Preview12!",
    "app.audio.auto-save-enabled=false", "app.audio.cleanup-enabled=false"
})
@Import(AudioTestDoubles.class)
class AudioIntegrationTest {
    @Autowired AudioService audio;
    @Autowired AudioFeedbackService feedback;
    @Autowired DictionaryService dictionary;
    @Autowired TtsSettingsService settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired AudioTestDoubles.TestSpeech speech;
    @Autowired AudioTestDoubles.TestObjects objects;
    @Autowired PrivateEntryService privateEntries;
    @Autowired AudioCleanupService cleanup;
    @Autowired LearningService learning;
    @Autowired PersonalContentService personal;
    @Autowired com.languagelean.learning.LearningExportService exports;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired UserAccountRepository accounts;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired com.languagelean.operations.SystemUsageService systemUsage;
    UUID actor;

    /** 复用不计数；合成失败、上传失败及有效重试分别记录，字符数按码点而不是UTF16长度。 */
    @Test void generationUsageTracksAttemptsWithoutPrivateContentsOrCachedHits() {
        var before = systemUsage.overview(null).generation(); var entry = entry("あ𠮷");
        assertEquals(AudioGenerationPort.Status.READY, ensure(entry).status());
        assertEquals(AudioGenerationPort.Status.READY, ensure(entry).status());
        assertEquals(1, speech.calls.get());
        speech.fail = true;
        assertEquals(AudioGenerationPort.Status.FAILED, audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true).status());
        speech.fail = false; objects.fail = true;
        assertEquals(AudioGenerationPort.Status.FAILED, audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true).status());
        objects.fail = false;
        assertEquals(AudioGenerationPort.Status.READY, audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true).status());
        var snapshot = systemUsage.overview(null); var after = snapshot.generation();
        assertEquals(4, after.requests() - before.requests()); assertEquals(8, after.inputCharacters() - before.inputCharacters());
        assertEquals(3, after.returned() - before.returned()); assertTrue(after.responseBytes() > before.responseBytes());
        assertEquals(2, after.ready() - before.ready()); assertEquals(2, after.failed() - before.failed());
        assertEquals(0, after.unconfirmed() - before.unconfirmed());
        var body = json.writeValueAsString(snapshot);
        assertFalse(body.contains("あ𠮷")); assertFalse(body.contains(actor.toString())); assertFalse(body.contains(entry.id().toString()));
        assertFalse(body.contains("objectKey")); assertFalse(body.contains("passwordHash"));
        var calls = speech.calls.get(); var reads = objects.reads.get();
        systemUsage.overview(null); assertEquals(calls, speech.calls.get()); assertEquals(reads, objects.reads.get());
    }

    /** UTC月末只计算开始时刻在该月的请求，包含跨月完成及未确认尝试。 */
    @Test void monthlyUsageUsesExclusiveUtcEndAndPreservesUnconfirmedOutcomes() {
        var start = java.time.Instant.parse("1999-06-01T00:00:00Z"); var end = java.time.Instant.parse("1999-07-01T00:00:00Z");
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try {
            usageRow(ids.get(0), start.minusMillis(1), "READY", start, 100L);
            usageRow(ids.get(1), start, "READY", end.plusSeconds(1), 100L);
            usageRow(ids.get(2), end.minusMillis(1), "REQUESTED", null, null);
            usageRow(ids.get(3), end, "FAILED", end, null);
            var stats = systemUsage.overview("1999-06");
            assertEquals("UTC", stats.timezone()); assertEquals(2, stats.generation().requests());
            assertEquals(6, stats.generation().inputCharacters()); assertEquals(100, stats.generation().responseBytes());
            assertEquals(1, stats.generation().ready()); assertEquals(1, stats.generation().unconfirmed()); assertEquals(0, stats.generation().failed());
        } finally { ids.forEach(id -> jdbc.update("delete from audio_generation_usage where id = ?", id)); }
    }
    private void usageRow(UUID id, java.time.Instant requested, String outcome, java.time.Instant completed, Long bytes) {
        jdbc.update("insert into audio_generation_usage(id, requested_at, completed_at, provider, model, input_characters, response_bytes, outcome) values (?, ?, ?, 'GOOGLE', 'Chirp3-HD', 3, ?, ?)",
                id, java.time.OffsetDateTime.ofInstant(requested, java.time.ZoneOffset.UTC), completed == null ? null : java.time.OffsetDateTime.ofInstant(completed, java.time.ZoneOffset.UTC), bytes, outcome);
    }

    /** 没有发音、语言关闭、服务商未配置均不算已经发起合成。 */
    @Test void blockedGenerationDoesNotCreateUsageRecords() {
        var before = systemUsage.overview(null).generation().requests();
        assertEquals(AudioGenerationPort.Status.MISSING_PRONUNCIATION, ensure(entry("")).status());
        var word = entry("ねこ"); speech.enabled = false;
        assertEquals(AudioGenerationPort.Status.NOT_CONFIGURED, ensure(word).status()); speech.enabled = true;
        var setting = settings.list().getFirst();
        settings.save("ja", new TtsSettingsService.Edit("GOOGLE", "Chirp3-HD", "ja-JP-Chirp3-HD-Aoede", false, true, setting.version()));
        assertEquals(AudioGenerationPort.Status.DISABLED, ensure(word).status());
        assertEquals(before, systemUsage.overview(null).generation().requests()); assertEquals(0, speech.calls.get());
    }

    @BeforeEach void setup() {
        actor = jdbc.queryForObject("select id from user_account where username = 'audio-editor'", UUID.class);
        speech.reset(); objects.reset();
        jdbc.update("delete from audio_object_cleanup");
        var setting = settings.list().getFirst();
        settings.save("ja", new TtsSettingsService.Edit("GOOGLE", "Chirp3-HD", "ja-JP-Chirp3-HD-Aoede", true, true, setting.version()));
    }

    /** 学习备份带全部可授权音频的版本信息，云对象、管理员草稿及另一账号私人音频不泄露。 */
    @Test void backupIncludesReferencedAudioMetadataWithoutCloudKeysOrForeignResources() {
        var publicEntry = entry("ねこ"); var publicClip = ensure(publicEntry);
        var unrelated = entry("いぬ"); var unrelatedClip = ensure(unrelated);
        var privateEntry = privateEntry(); var privateClip = audio.ensureResource(privateEntry.id(), AudioService.Scope.PERSONAL,
            AudioService.Kind.WORD, privateEntry.content().readings().getFirst().id(), actor, false, false);
        var book = learning.createWordbook(actor, new LearningService.CreateWordbook("音频备份" + UUID.randomUUID(), ""));
        learning.addDictionaryEntry(actor, book.id(), publicEntry.id());
        var suffix = UUID.randomUUID().toString();
        var other = accounts.saveAndFlush(UserAccountEntity.create("backup-" + suffix, suffix + "@example.test", encoder.encode("Preview12!"), Set.of(Role.USER)));
        var foreign = privateEntries.create(other.getId(), new PrivateEntryService.Create("ja", "Jpan", "他人词" + suffix, content("いぬ")));
        var foreignClip = audio.ensureResource(foreign.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD,
            foreign.content().readings().getFirst().id(), other.getId(), false, false);
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "草稿" + suffix, content("くさ")), actor);
        var draftClip = audio.ensureResource(draft.id(), AudioService.Scope.DRAFT, AudioService.Kind.WORD, draft.draft().readings().getFirst().id(), true, false);
        var calls = speech.calls.get(); var reads = objects.reads.get();
        var backup = exports.export(actor);
        var ids = backup.audioResources().stream().flatMap(asset -> asset.versions().stream()).map(AudioBackupService.Version::id).toList();
        assertTrue(ids.contains(publicClip.audioVersionId())); assertTrue(ids.contains(privateClip.audioVersionId()));
        assertFalse(ids.contains(unrelatedClip.audioVersionId()));
        assertFalse(ids.contains(foreignClip.audioVersionId())); assertFalse(ids.contains(draftClip.audioVersionId()));
        assertEquals(List.of(foreignClip.audioVersionId()), exports.export(other.getId()).audioResources().stream().flatMap(asset -> asset.versions().stream()).map(AudioBackupService.Version::id).toList());
        assertTrue(exports.export(UUID.randomUUID()).audioResources().isEmpty());
        var serialized = json.writeValueAsString(backup);
        assertFalse(serialized.contains("objectKey")); assertFalse(serialized.contains("generationToken"));
        assertFalse(serialized.contains("audio/personal/"));
        assertEquals(calls, speech.calls.get()); assertEquals(reads, objects.reads.get());
    }

    /** 未填写发音时不创建任务、资产或调用服务商，也不自动用假名代替。 */
    @Test void missingPronunciationDoesNotGenerate() {
        var entry = entry("");
        var before = jdbc.queryForObject("select count(*) from audio_asset", Integer.class);
        assertEquals(AudioGenerationPort.Status.MISSING_PRONUNCIATION, ensure(entry).status());
        assertEquals(0, speech.calls.get());
        assertEquals(before, jdbc.queryForObject("select count(*) from audio_asset", Integer.class));
    }

    /** 多个请求同时打开同一音频，只允许一个工作者调用 TTS。 */
    @Test void concurrentRequestsShareOneGenerationLease() throws Exception {
        var beforeUsage = systemUsage.overview(null).generation().requests();
        var entry = entry("ねこ");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        speech.callback = text -> { entered.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); } };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> ensure(entry));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(AudioGenerationPort.Status.PENDING, ensure(entry).status());
                assertEquals(1, speech.calls.get());
            } finally { release.countDown(); }
            var ready = first.get(10, TimeUnit.SECONDS);
            assertEquals(AudioGenerationPort.Status.READY, ready.status());
            assertEquals(ready.audioVersionId(), ensure(entry).audioVersionId());
            assertEquals(1, speech.calls.get());
            assertEquals(1, systemUsage.overview(null).generation().requests() - beforeUsage);
            assertTrue(audio.content(ready.audioVersionId(), false).length > 100);
        }
    }

    /** 统计开始事务失败时不调用云服务；失败认领释放后仍能重新生成。 */
    @Test void usageReservationFailureDoesNotCallTheProvider() {
        var word = entry("あ".repeat(227)); var before = systemUsage.overview(null).generation().requests();
        jdbc.execute("alter table audio_generation_usage add constraint test_usage_insert check(input_characters <> 227)");
        try {
            assertEquals(AudioGenerationPort.Status.FAILED, ensure(word).status()); assertEquals(0, speech.calls.get());
            assertEquals(before, systemUsage.overview(null).generation().requests());
        } finally { jdbc.execute("alter table audio_generation_usage drop constraint test_usage_insert"); }
        assertEquals(AudioGenerationPort.Status.READY, ensure(word).status()); assertEquals(1, speech.calls.get());
    }

    /** 统计最终确认失败不能破坏已切换的可用版本，数据库保留未确认而非虚报成功。 */
    @Test void usageCompletionFailureKeepsTheGeneratedAudioAndAnUnconfirmedRecord() {
        var word = entry("あ".repeat(223)); var before = systemUsage.overview(null).generation().unconfirmed();
        jdbc.execute("alter table audio_generation_usage add constraint test_usage_finish check(input_characters <> 223 or outcome <> 'READY')");
        AudioGenerationPort.Result result;
        try { result = ensure(word); assertEquals(AudioGenerationPort.Status.READY, result.status()); }
        finally { jdbc.execute("alter table audio_generation_usage drop constraint test_usage_finish"); }
        assertEquals(before + 1, systemUsage.overview(null).generation().unconfirmed());
        assertTrue(audio.content(result.audioVersionId(), false).length > 0);
        assertEquals(result.audioVersionId(), ensure(word).audioVersionId()); assertEquals(1, speech.calls.get());
    }

    /** 文件缺失或被破坏时生成新版本；旧版本元数据保留用于追踪。 */
    @Test void repairsMissingAndCorruptFiles() {
        var entry = entry("ねこ");
        var first = ensure(entry);
        objects.files.clear();
        var second = ensure(entry);
        assertNotEquals(first.audioVersionId(), second.audioVersionId());
        objects.files.replaceAll((key, bytes) -> new byte[]{1, 2, 3});
        var third = ensure(entry);
        assertNotEquals(second.audioVersionId(), third.audioVersionId());
        assertEquals(3, speech.calls.get());
        assertEquals(3, jdbc.queryForObject("select count(*) from audio_version where audio_asset_id = (select id from audio_asset where dictionary_entry_id = ? and content_scope = 'PUBLISHED')", Integer.class, entry.id()));
    }

    /** 强制重新生成上传失败时旧版本仍可播放，租约被释放允许重试。 */
    @Test void uploadFailureKeepsOldVersionAndReleasesLease() {
        var entry = entry("ねこ"); var old = ensure(entry);
        objects.fail = true;
        var failed = audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true);
        assertEquals(AudioGenerationPort.Status.FAILED, failed.status());
        assertEquals(old.audioVersionId(), failed.audioVersionId());
        assertTrue(audio.content(old.audioVersionId(), false).length > 100);
        assertEquals(0, jdbc.queryForObject("select count(*) from audio_asset where dictionary_entry_id = ? and generation_token is not null", Integer.class, entry.id()));
        objects.fail = false;
        assertNotEquals(old.audioVersionId(), audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true).audioVersionId());
    }

    /** 草稿与公开音频分开；普通用户不能通过已知版本 UUID 读取管理员草稿。 */
    @Test void separatesDraftAndPublishedAudioAndChecksBans() {
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "draft-" + UUID.randomUUID(), content("ねこ")), actor);
        var resource = draft.draft().readings().getFirst().id();
        rejects(403, () -> audio.ensureResource(draft.id(), AudioService.Scope.DRAFT, AudioService.Kind.WORD, resource, false, false));
        var privateClip = audio.ensureResource(draft.id(), AudioService.Scope.DRAFT, AudioService.Kind.WORD, resource, true, false);
        rejects(403, () -> audio.content(privateClip.audioVersionId(), false));
        var published = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试"), actor);
        var publicClip = ensure(published);
        assertNotEquals(privateClip.audioVersionId(), publicClip.audioVersionId());
        dictionary.ban(published.id(), new DictionaryService.Action(published.version(), "测试封禁"), actor);
        rejects(404, () -> audio.content(publicClip.audioVersionId(), false));
        rejects(404, () -> ensure(published));
    }

    /** 关闭生成不影响已上传文件播放，修改声音必须产生新版本且使用配置版本保护。 */
    @Test void disabledGenerationStillPlaysExistingAudioAndSettingsUseVersions() {
        var entry = entry("ねこ"); var old = ensure(entry);
        var original = settings.list().getFirst();
        var disabled = settings.save("ja", new TtsSettingsService.Edit("GOOGLE", "Chirp3-HD", original.voice(), false, false, original.version()));
        assertEquals(old.audioVersionId(), ensure(entry).audioVersionId());
        assertEquals(AudioGenerationPort.Status.DISABLED, ensure(entry("いぬ")).status());
        rejects(409, () -> settings.save("ja", new TtsSettingsService.Edit("GOOGLE", "Chirp3-HD", original.voice(), true, true, original.version())));
        rejects(400, () -> settings.save("ja", new TtsSettingsService.Edit("GOOGLE", "Chirp3-HD", "en-US-Chirp3-HD-Aoede", true, true, disabled.version())));
        settings.save("ja", new TtsSettingsService.Edit("GOOGLE", "Chirp3-HD", "ja-JP-Chirp3-HD-Kore", true, true, disabled.version()));
        assertNotEquals(old.audioVersionId(), ensure(entry).audioVersionId());
    }

    /** 生成途中内容变更不能将旧输入切为当前版本，下一次请求按新内容补齐。 */
    @Test void contentChangeDuringGenerationDoesNotPublishStaleAudio() {
        var before = systemUsage.overview(null).generation().discarded();
        var entry = entry("ねこ");
        speech.callback = text -> {
            speech.callback = null;
            var changed = new DictionaryContent(1, List.of(new DictionaryContent.Reading(reading(entry), "いぬ", "いぬ")), entry.published().senses(), "手工录入", "");
            var saved = dictionary.save(entry.id(), new DictionaryService.Edit(entry.version(), changed), actor);
            dictionary.publish(saved.id(), new DictionaryService.Action(saved.version(), "修改发音"), actor);
        };
        assertEquals(AudioGenerationPort.Status.PENDING, ensure(entry).status());
        assertEquals(before + 1, systemUsage.overview(null).generation().discarded());
        assertNull(jdbc.queryForObject("select current_version_id from audio_asset where dictionary_entry_id = ? and content_scope = 'PUBLISHED'", UUID.class, entry.id()));
        assertEquals(AudioGenerationPort.Status.READY, ensure(dictionary.adminDetail(entry.id())).status());
    }

    /** 同一例句与单词采用独立音频，空例句发音不生成。 */
    @Test void examplePronunciationIsIndependent() {
        var example = new DictionaryContent.Example(UUID.randomUUID(), "猫がいる。", "ねこがいる。", "有猫。");
        var content = new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ")),
                List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", "猫", List.of(example))), "手工录入", "");
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "example-" + UUID.randomUUID(), content), actor);
        var entry = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试"), actor);
        var word = ensure(entry);
        var clip = audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.EXAMPLE, example.id(), false, false);
        assertNotEquals(word.audioVersionId(), clip.audioVersionId());
        assertNotEquals(word.textHash(), clip.textHash());
    }

    /** 私人文件、生成、强制生成和管理员访问均校验本人。 */
    @Test void privateAudioIsOwnerOnlyAndUsesDistinctNamespace() {
        var entry = privateEntry(); var reading = entry.content().readings().getFirst().id();
        rejects(404, () -> audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, UUID.randomUUID(), true, false));
        var result = audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false);
        assertEquals(AudioGenerationPort.Status.READY, result.status()); assertEquals(1, speech.calls.get());
        assertTrue(audio.content(result.audioVersionId(), actor, false).length > 100);
        rejects(404, () -> audio.content(result.audioVersionId(), UUID.randomUUID(), true));
        assertTrue(objects.files.keySet().stream().allMatch(key -> key.startsWith("audio/personal/")));
        assertEquals(0, jdbc.queryForObject("select count(*) from audio_asset where personal_custom_entry_id = ? and dictionary_entry_id is not null", Integer.class, entry.id()));
    }
    /** 个人配置关闭后保存和点击都不能补生成，已有正确版本可播放。 */
    @Test void personalSettingIsEnforcedForClickForceAndSavedGeneration() {
        var entry = privateEntry(); var reading = entry.content().readings().getFirst().id();
        var ready = audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false);
        var setting = settings.list().getFirst(); settings.save("ja", new TtsSettingsService.Edit(setting.provider(), setting.model(), setting.voice(), true, false, setting.version()));
        assertEquals(AudioGenerationPort.Status.READY, audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false).status());
        assertEquals(AudioGenerationPort.Status.DISABLED, audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, true).status());
        var fresh = privateEntry(); audio.generatePersonalSaved(fresh.id(), actor);
        assertEquals(AudioGenerationPort.Status.DISABLED, audio.ensureResource(fresh.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, fresh.content().readings().getFirst().id(), actor, false, false).status());
        assertEquals(1, speech.calls.get()); assertTrue(audio.content(ready.audioVersionId(), actor, false).length > 100);
    }
    /** 私人保存生成涵盖词条和例句，缺少发音的子项不生成。 */
    @Test void privateSaveGeneratesWordsAndExamplesAndSkipsEmptyPronunciation() {
        var example = new DictionaryContent.Example(UUID.randomUUID(), "例句", "ねこ", "译文");
        var value = new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", "ねこ"), new DictionaryContent.Reading(UUID.randomUUID(), "blank", "")),
            List.of(new DictionaryContent.Sense(UUID.randomUUID(), "", "", List.of(example))), "", "");
        var entry = privateEntries.create(actor, new PrivateEntryService.Create("ja", "Jpan", "private-example-" + UUID.randomUUID(), value));
        audio.generatePersonalSaved(entry.id(), actor); assertEquals(2, speech.calls.get());
        assertEquals(2, jdbc.queryForObject("select count(*) from audio_asset where personal_custom_entry_id = ?", Integer.class, entry.id()));
    }
    /** 删除立即清除数据库和访问权限；云失败保留任务，恢复后删除全部版本。 */
    @Test void privateDeleteQueuesCloudCleanupAndRetriesWithoutRestoringContent() {
        var entry = privateEntry(); var reading = entry.content().readings().getFirst().id();
        var first = audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false);
        audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, true);
        privateEntries.delete(actor, entry.id()); rejects(404, () -> audio.content(first.audioVersionId(), actor, false));
        assertEquals(2, jdbc.queryForObject("select count(*) from audio_object_cleanup", Integer.class));
        objects.fail = true; assertEquals(0, cleanup.drain()); assertEquals(2, objects.files.size());
        objects.fail = false; assertEquals(2, cleanup.drain()); assertTrue(objects.files.isEmpty());
        assertEquals(0, jdbc.queryForObject("select count(*) from audio_object_cleanup", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from audio_asset where personal_custom_entry_id = ?", Integer.class, entry.id()));
    }
    /** 云生成期间删除词条，迟到上传不能创建新版本，孤立文件仍进入删除任务。 */
    @Test void deletionDuringPrivateGenerationDoesNotLeakUploadedObject() {
        var entry = privateEntry(); var reading = entry.content().readings().getFirst().id();
        speech.callback = text -> { speech.callback = null; privateEntries.delete(actor, entry.id()); };
        assertEquals(AudioGenerationPort.Status.FAILED, audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from audio_object_cleanup", Integer.class));
        assertEquals(1, cleanup.drain()); assertTrue(objects.files.isEmpty());
        rejects(404, () -> privateEntries.detail(actor, entry.id()));
    }
    /** 私人请求复用跨进程锁，重复点击不会多次调用云服务。 */
    @Test void concurrentPrivateClicksShareTheSameDatabaseLease() throws Exception {
        var entry = privateEntry(); var reading = entry.content().readings().getFirst().id();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        speech.callback = text -> { entered.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); } };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false));
            try { assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(AudioGenerationPort.Status.PENDING, audio.ensureResource(entry.id(), AudioService.Scope.PERSONAL, AudioService.Kind.WORD, reading, actor, false, false).status());
                assertEquals(1, speech.calls.get());
            } finally { release.countDown(); }
            assertEquals(AudioGenerationPort.Status.READY, first.get(10, TimeUnit.SECONDS).status());
        }
    }
    /** 覆盖发音与公共资源身份分离，只允许本人，并受个人开关及封禁约束。 */
    @Test void overrideAudioSeparatesPublicAudioAndChecksOwnerSettingsAndBan() {
        var entry = entry("ねこ"); var book = learning.createWordbook(actor, new LearningService.CreateWordbook("override-" + UUID.randomUUID(), ""));
        var item = learning.addDictionaryEntry(actor, book.id(), entry.id()); var reading = new DictionaryContent.Reading(reading(entry), "いぬ", "いぬ");
        personal.save(actor, item.id(), new PersonalContentService.Save(0L, null, "", List.of(), true, List.of(reading), null));
        var publicClip = ensure(entry);
        var clip = audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading.id(), actor, false, false);
        assertEquals(AudioGenerationPort.Status.READY, clip.status()); assertNotEquals(publicClip.audioVersionId(), clip.audioVersionId());
        assertTrue(objects.files.keySet().stream().anyMatch(key -> key.startsWith("audio/override/")));
        rejects(404, () -> audio.content(clip.audioVersionId(), UUID.randomUUID(), true));
        rejects(404, () -> audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading.id(), UUID.randomUUID(), true, true));
        var config = settings.list().getFirst(); settings.save("ja", new TtsSettingsService.Edit(config.provider(), config.model(), config.voice(), true, false, config.version()));
        assertEquals(AudioGenerationPort.Status.READY, audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading.id(), actor, false, false).status());
        assertEquals(AudioGenerationPort.Status.DISABLED, audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading.id(), actor, false, true).status());
        dictionary.ban(entry.id(), new DictionaryService.Action(entry.version(), "ban"), actor);
        rejects(404, () -> audio.content(clip.audioVersionId(), actor, false));
    }

    /** 清空覆盖撤销私人资源及云对象，不能由旧版本URL恢复，公共发音保留。 */
    @Test void clearingOverridesQueuesOnlyTheirAudioAndInheritedAudioIsPublic() {
        var entry = entry("ねこ"); var book = learning.createWordbook(actor, new LearningService.CreateWordbook("override-" + UUID.randomUUID(), ""));
        var item = learning.addDictionaryEntry(actor, book.id(), entry.id()); var example = new DictionaryContent.Example(UUID.randomUUID(), "私人", "ねこ", "译文");
        var senses = List.of(new DictionaryContent.Sense(UUID.randomUUID(), "", "个人", List.of(example)));
        personal.save(actor, item.id(), new PersonalContentService.Save(0L, null, "", List.of(), true, null, senses));
        var publicClip = ensure(entry); audio.generateOverrideSaved(item.id(), actor); assertEquals(2, speech.calls.get());
        var clip = audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.EXAMPLE, example.id(), actor, false, false);
        rejects(404, () -> audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading(entry), actor, false, false));
        personal.save(actor, item.id(), new PersonalContentService.Save(1L, null, "", List.of(), true, null, null));
        rejects(404, () -> audio.content(clip.audioVersionId(), actor, false));
        assertEquals(1, cleanup.drain()); assertTrue(audio.content(publicClip.audioVersionId(), false).length > 100);
        assertEquals(0, jdbc.queryForObject("select count(*) from audio_asset where learning_item_id = ?", Integer.class, item.id()));
    }

    /** 最后关联删除回收全部个人版本，生成途中删除的迟到上传也进入清理。 */
    @Test void finalLearningDeletionCleansOverrideAudioIncludingInFlightGeneration() {
        var entry = entry("ねこ"); var book = learning.createWordbook(actor, new LearningService.CreateWordbook("override-" + UUID.randomUUID(), ""));
        var item = learning.addDictionaryEntry(actor, book.id(), entry.id());
        personal.save(actor, item.id(), new PersonalContentService.Save(0L, null, "", List.of(), true, entry.published().readings(), null));
        speech.callback = text -> { speech.callback = null; learning.removeLearningItem(actor, book.id(), item.id()); };
        assertEquals(AudioGenerationPort.Status.FAILED, audio.ensureResource(item.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading(entry), actor, false, false).status());
        assertEquals(1, cleanup.drain()); assertTrue(objects.files.isEmpty());
        var fresh = learning.addDictionaryEntry(actor, book.id(), entry.id());
        personal.save(actor, fresh.id(), new PersonalContentService.Save(0L, null, "", List.of(), true, entry.published().readings(), null));
        audio.generateOverrideSaved(fresh.id(), actor);
        audio.ensureResource(fresh.id(), AudioService.Scope.OVERRIDE, AudioService.Kind.WORD, reading(entry), actor, false, true);
        learning.deleteWordbook(actor, book.id()); assertEquals(2, cleanup.drain()); assertTrue(objects.files.isEmpty());
        assertEquals("PUBLISHED", dictionary.detail(entry.id()).status());
    }

    /** 同一提交只创建一条反馈，错误范围、过期发音和他人请求标识不能复用。 */
    @Test void feedbackIsIdempotentAndOnlyAcceptsPublicResources() {
        var entry = entry("ねこ"); var clip = ensure(entry); var request = feedbackRequest(entry, clip.audioVersionId());
        var first = feedback.submit(actor, request); assertEquals(first.id(), feedback.submit(actor, request).id());
        assertEquals(1, jdbc.queryForObject("select count(*) from audio_feedback where id=?", Integer.class, first.id()));
        rejects(409, () -> feedback.submit(UUID.randomUUID(), request));
        rejects(409, () -> feedback.submit(actor, new AudioFeedbackService.Submit(request.id(), entry.id(), reading(entry), AudioService.Kind.WORD, "UNPLAYABLE", "ねこ", clip.audioVersionId(), "different")));
        var other = entry("いぬ"); var otherClip = ensure(other);
        rejects(400, () -> feedback.submit(actor, feedbackRequest(entry, otherClip.audioVersionId())));
        rejects(409, () -> feedback.submit(actor, new AudioFeedbackService.Submit(UUID.randomUUID(), entry.id(), reading(entry), AudioService.Kind.WORD, "UNPLAYABLE", "古い発音", null, "异常")));
        var privateEntry = privateEntry();
        rejects(404, () -> feedback.submit(actor, new AudioFeedbackService.Submit(UUID.randomUUID(), privateEntry.id(), privateEntry.content().readings().getFirst().id(), AudioService.Kind.WORD, "UNPLAYABLE", "ねこ", null, "私密内容")));
        assertTrue(feedback.list(UUID.randomUUID(), "", null, null, "", 0).items().isEmpty());
        assertFalse(json.writeValueAsString(first).contains("objectKey"));
    }

    /** 云失败或旧音频不能误结单，成功生成后才允许修复，并拒绝陈旧管理员覆盖。 */
    @Test void feedbackRequiresNewMatchingAudioBeforeResolution() {
        var entry = entry("ねこ"); var clip = ensure(entry); var report = feedback.submit(actor, feedbackRequest(entry, clip.audioVersionId()));
        var withoutReportedVersion = feedback.submit(actor, feedbackRequest(entry, null));
        rejects(409, () -> feedback.resolve(actor, withoutReportedVersion.id(), new AudioFeedbackService.Action(0, "RESOLVED", "不能把已有音频当作修复")));
        var resolution = new AudioFeedbackService.Action(report.version(), "RESOLVED", "已修正声调并试听");
        rejects(409, () -> feedback.resolve(actor, report.id(), resolution));
        objects.fail = true; assertEquals(AudioGenerationPort.Status.FAILED, audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true).status());
        assertEquals("PENDING", feedback.detail(report.id()).report().status()); rejects(409, () -> feedback.resolve(actor, report.id(), resolution));
        objects.fail = false; var ready = audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true);
        var resolved = feedback.resolve(actor, report.id(), resolution); assertEquals("RESOLVED", resolved.status()); assertEquals(report.version() + 1, resolved.version());
        assertEquals(resolved.version(), feedback.resolve(actor, report.id(), resolution).version());
        rejects(409, () -> feedback.resolve(actor, report.id(), new AudioFeedbackService.Action(report.version(), "DISMISSED", "另一个管理员")));
        assertEquals(ready.audioVersionId(), feedback.detail(report.id()).currentAudioVersionId());
    }

    /** 例句也可反馈；公开资源封禁后不能生成，必须说明原因再关闭。 */
    @Test void feedbackExamplesAndUnavailablePublicResourcesRemainTraceable() {
        var example = new DictionaryContent.Example(UUID.randomUUID(), "猫がいる。", "ねこがいる", "有猫。");
        var data = new DictionaryContent(1, List.of(), List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", "猫", List.of(example))), "手工录入", "");
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "例句" + UUID.randomUUID(), data), actor);
        var entry = dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试"), actor);
        var report = feedback.submit(actor, new AudioFeedbackService.Submit(UUID.randomUUID(), entry.id(), example.id(), AudioService.Kind.EXAMPLE, "UNPLAYABLE", "ねこがいる", null, "无法播放"));
        assertEquals(0, speech.calls.get()); assertEquals("EXAMPLE", report.kind());
        dictionary.ban(entry.id(), new DictionaryService.Action(entry.version(), "例句需要纠错"), actor);
        assertFalse(feedback.detail(report.id()).available()); assertEquals("ねこがいる", feedback.detail(report.id()).report().pronunciationText());
        rejects(409, () -> feedback.prepareGeneration(report.id(), report.version()));
        rejects(400, () -> feedback.resolve(actor, report.id(), new AudioFeedbackService.Action(report.version(), "DISMISSED", "")));
        assertEquals("DISMISSED", feedback.resolve(actor, report.id(), new AudioFeedbackService.Action(report.version(), "DISMISSED", "词条已封禁，等待重新审核")).status());
    }

    /** 文本已发布新版本、尚未生成对应音频时，旧音频不能作为已修复凭据。 */
    @Test void feedbackDoesNotTreatMismatchedAudioAsRepaired() {
        var entry = entry("ねこ"); var report = feedback.submit(actor, feedbackRequest(entry, null)); ensure(entry);
        var old = entry.published(); var readings = List.of(new DictionaryContent.Reading(reading(entry), "ねこ", "ネコ"));
        var changed = new DictionaryContent(1, readings, old.senses(), old.sourceName(), old.license());
        var draft = dictionary.save(entry.id(), new DictionaryService.Edit(entry.version(), changed), actor);
        dictionary.publish(entry.id(), new DictionaryService.Action(draft.version(), "修正"), actor);
        rejects(409, () -> feedback.resolve(actor, report.id(), new AudioFeedbackService.Action(report.version(), "RESOLVED", "已修正")));
        audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), true, true);
        assertEquals("RESOLVED", feedback.resolve(actor, report.id(), new AudioFeedbackService.Action(report.version(), "RESOLVED", "已修正并重生成")).status());
    }
    /** 公共发音反馈测试使用独立请求标识，明确记录实际音频版本。 */
    private AudioFeedbackService.Submit feedbackRequest(DictionaryService.AdminView entry, UUID version) {
        return new AudioFeedbackService.Submit(UUID.randomUUID(), entry.id(), reading(entry), AudioService.Kind.WORD, "WRONG_PRONUNCIATION", "ねこ", version, "声调不正确");
    }

    /** 独立个人资源，不依赖公共词典或其他测试身份。 */
    private PrivateEntryService.View privateEntry() {
        return privateEntries.create(actor, new PrivateEntryService.Create("ja", "Jpan", "private-audio-" + UUID.randomUUID(), content("ねこ")));
    }
    /** 独立测试词条，不依赖执行顺序。 */
    private DictionaryService.AdminView entry(String pronunciation) {
        var draft = dictionary.create(new DictionaryService.Create("ja", "Jpan", "audio-" + UUID.randomUUID(), content(pronunciation)), actor);
        return dictionary.publish(draft.id(), new DictionaryService.Action(draft.version(), "测试"), actor);
    }
    private DictionaryContent content(String pronunciation) {
        return new DictionaryContent(1, List.of(new DictionaryContent.Reading(UUID.randomUUID(), "ねこ", pronunciation)),
                List.of(new DictionaryContent.Sense(UUID.randomUUID(), "名词", "猫", List.of())), "手工录入", "");
    }
    private UUID reading(DictionaryService.AdminView entry) { return entry.published().readings().getFirst().id(); }
    private AudioGenerationPort.Result ensure(DictionaryService.AdminView entry) {
        return audio.ensureResource(entry.id(), AudioService.Scope.PUBLISHED, AudioService.Kind.WORD, reading(entry), false, false);
    }
    private void rejects(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
}
