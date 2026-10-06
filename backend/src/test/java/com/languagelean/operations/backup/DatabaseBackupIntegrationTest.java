package com.languagelean.operations.backup;

import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:backups;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__language_configuration.sql,classpath:db/migration/V2__user_accounts.sql,classpath:db/migration/V3__dictionary.sql,classpath:db/migration/V4__language_edit_version.sql,classpath:db/migration/V5__dictionary_import.sql,classpath:db/migration/V6__system_dictionary.sql,classpath:db/migration/V7__dictionary_source_release.sql,classpath:db/migration/V8__learning_items_and_wordbooks.sql,classpath:db/migration/V9__review_events.sql,classpath:audio/v10-h2.sql,classpath:db/migration/V11__personal_entry_overrides.sql,classpath:db/migration/V12__private_entries.sql,classpath:db/migration/V13__personal_pronunciations_and_examples.sql,classpath:db/migration/V14__dictionary_contributions.sql,classpath:dictionary/v15-h2.sql,classpath:db/migration/V16__native_language.sql,classpath:accounts/v17-h2.sql,classpath:db/migration/V18__account_closure.sql,classpath:db/migration/V19__audio_feedback.sql,classpath:db/migration/V20__password_transport_keys.sql,classpath:db/migration/V21__audio_generation_usage.sql,classpath:db/migration/V22__database_backups.sql",
    "app.backup.enabled=false", "app.audio.cleanup-enabled=false"
})
@Import(DatabaseBackupIntegrationTest.Doubles.class)
class DatabaseBackupIntegrationTest {
    @Autowired DatabaseBackupConfiguration config;
    @Autowired DatabaseBackupService service;
    @Autowired DatabaseBackupRunner runner;
    @Autowired DatabaseBackupTransactions transactions;
    @Autowired TestDump dump;
    @Autowired TestStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @TempDir Path directory;
    @BeforeEach void setup() {
        jdbc.update("delete from database_backup_object"); jdbc.update("delete from database_backup_job");
        jdbc.update("update database_backup_control set active_job = null, lease_until = null, next_scheduled_at = null");
        config.setEnabled(true); config.setIntervalHours(0); config.setRetentionDays(0); config.setKeyId("test-key-v1");
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key); config.setEncryptionKey(Base64.getEncoder().encodeToString(key));
        dump.directory = directory; dump.calls = 0; dump.fail = false; dump.callback = null;
        store.files.clear(); store.deletes = 0; store.failUpload = false; store.failDelete = false; store.scope = "0".repeat(64);
    }
    private UUID request() { var id = UUID.randomUUID(); assertEquals("QUEUED", service.request(id, UUID.randomUUID()).state()); return id; }
    private DatabaseBackupService.Job job(UUID id) { return service.overview().jobs().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow(); }

    @Test void successfulBackupIsEncryptedVerifiedIdempotentAndDoesNotExposeSecrets() throws Exception {
        var id = request(); assertEquals(id, service.request(id, UUID.randomUUID()).id());
        runner.runOnce(); assertEquals("SUCCESS", job(id).state()); assertEquals("VERIFIED", job(id).archiveState()); assertEquals(1, dump.calls);
        runner.runOnce(); assertEquals(id, service.request(id, UUID.randomUUID()).id()); assertEquals(1, dump.calls);
        var stored = store.files.values().iterator().next(); var archive = directory.resolve("saved.llbackup"); Files.write(archive, stored);
        var restored = directory.resolve("verified.pgdump"); BackupArchive.decrypt(archive, restored, config.getKeyId(), config.getEncryptionKey());
        assertEquals(TestDump.CONTENT, Files.readString(restored));
        var response = json.writeValueAsString(service.overview()); assertFalse(response.contains(TestDump.CONTENT));
        assertFalse(response.contains(config.getEncryptionKey())); assertFalse(response.contains("database-backups/")); assertFalse(response.contains("runToken"));
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".partial"))); }
    }
    @Test void disabledOrIncompleteConfigurationNeverCreatesDumpOrJob() {
        config.setEnabled(false); assertThrows(org.springframework.web.server.ResponseStatusException.class, this::request); runner.runOnce(); assertEquals(0, dump.calls);
        config.setEnabled(true); config.setEncryptionKey("invalid"); assertFalse(service.overview().ready()); runner.runOnce(); assertEquals(0, dump.calls);
        assertEquals(0, jdbc.queryForObject("select count(*) from database_backup_job", Integer.class));
    }
    @Test void uploadFailureRetainsOnlySafeFailureMetadataAndRetriesOrphanCleanup() {
        var id = request(); store.failUpload = true; store.failDelete = true; runner.runOnce();
        assertEquals("FAILED", job(id).state()); assertEquals("UPLOAD_OR_VERIFICATION_FAILED", job(id).errorCode()); assertEquals("DELETE_PENDING", job(id).archiveState());
        store.failUpload = false; store.failDelete = false; runner.runOnce(); assertEquals("DELETED", job(id).archiveState()); assertEquals(1, dump.calls);
        assertFalse(json.writeValueAsString(service.overview()).contains("secret-error"));
    }
    @Test void dumpFailureReleasesClaimAndDoesNotUploadAnything() {
        var id = request(); dump.fail = true; runner.runOnce(); assertEquals("FAILED", job(id).state()); assertEquals("DUMP_FAILED", job(id).errorCode());
        assertTrue(store.files.isEmpty()); dump.fail = false; var next = request(); runner.runOnce(); assertEquals("SUCCESS", job(next).state());
    }
    @Test void concurrentRunnerCannotClaimTheSameJobTwice() throws Exception {
        var id = request(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        dump.callback = () -> { entered.countDown(); try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); } };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(runner::runOnce);
            try { assertTrue(entered.await(10, TimeUnit.SECONDS)); runner.runOnce(); assertEquals(1, dump.calls); assertEquals("RUNNING", job(id).state()); }
            finally { release.countDown(); }
            running.get(10, TimeUnit.SECONDS); assertEquals("SUCCESS", job(id).state());
        }
    }
    @Test void retentionAlwaysKeepsNewestVerifiedArchiveAndRespectsStorageScope() {
        var first = request(); runner.runOnce(); var second = request(); runner.runOnce();
        jdbc.update("update database_backup_object set verified_at = ? where job_id = ?", OffsetDateTime.now().minusDays(30), first);
        jdbc.update("update database_backup_object set verified_at = ? where job_id = ?", OffsetDateTime.now().minusDays(10), second);
        config.setRetentionDays(7); runner.runOnce(); assertEquals("DELETED", job(first).archiveState()); assertEquals("VERIFIED", job(second).archiveState());
        store.scope = "1".repeat(64); var otherScope = request(); runner.runOnce(); store.scope = "0".repeat(64);
        runner.runOnce(); assertEquals("VERIFIED", job(second).archiveState()); assertEquals("VERIFIED", job(otherScope).archiveState());
    }
    @Test void interruptedLeaseIsFailedAndOldWorkerCannotPublishResults() {
        var id = request(); var claim = transactions.claim();
        transactions.stage(id, claim.runToken, store.scopeId(), config.getKeyId(), 300, "0".repeat(64));
        jdbc.update("update database_backup_control set lease_until = ?", OffsetDateTime.now().minusSeconds(1));
        runner.runOnce(); assertEquals("FAILED", job(id).state()); assertEquals("WORKER_INTERRUPTED", job(id).errorCode()); assertEquals("DELETED", job(id).archiveState());
        assertThrows(IllegalStateException.class, () -> transactions.complete(id, claim.runToken));
        transactions.fail(id, claim.runToken, "UPLOAD_OR_VERIFICATION_FAILED");
        assertEquals("DELETE_PENDING", job(id).archiveState()); runner.runOnce(); assertEquals("DELETED", job(id).archiveState());
    }
    @Test void scheduleDoesNotCatchUpWithUnboundedJobsAndZeroIntervalNeverSchedules() {
        runner.runOnce(); assertTrue(service.overview().jobs().isEmpty()); config.setIntervalHours(6);
        runner.runOnce(); assertEquals(1, dump.calls); assertEquals("SCHEDULED", service.overview().jobs().getFirst().triggerKind());
        runner.runOnce(); assertEquals(1, dump.calls);
    }
    @TestConfiguration static class Doubles {
        @Bean @Primary TestDump testDump(DatabaseBackupConfiguration config) { return new TestDump(config); }
        @Bean @Primary TestStore testStore() { return new TestStore(); }
    }
    static class TestDump implements DatabaseDumpPort {
        static final String CONTENT = "PGDMP-private-learning-snapshot";
        final DatabaseBackupConfiguration config; Path directory; int calls; boolean fail; Runnable callback;
        TestDump(DatabaseBackupConfiguration config) { this.config = config; }
        public boolean configured() { return true; }
        public Path encryptedDump() throws Exception {
            calls++; if (callback != null) callback.run(); if (fail) throw new IOException("secret-error");
            var path = BackupArchive.privateTemp(directory, "test-encrypted-");
            try (var output = BackupArchive.encrypt(Files.newOutputStream(path), config.getKeyId(), config.getEncryptionKey())) { output.write(CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            return path;
        }
    }
    static class TestStore implements BackupObjectStore {
        final Map<String, byte[]> files = new ConcurrentHashMap<>(); int deletes; boolean failUpload, failDelete; String scope = "0".repeat(64);
        public boolean configured() { return true; } public String scopeId() { return scope; }
        public void uploadAndVerify(String key, Path path, String sha) throws Exception {
            files.put(key, Files.readAllBytes(path)); if (failUpload) throw new IOException("secret-error"); assertEquals(sha, BackupArchive.sha256(path));
        }
        public void delete(String key) { if (failDelete) throw new IllegalStateException("secret-error"); deletes++; files.remove(key); }
    }
}
