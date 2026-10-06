package com.languagelean.operations.backup;

import java.nio.file.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 全部长时间IO在事务之外；成功必须经过转储、加密、上传、云回读和状态确认。 */
@Service
class DatabaseBackupRunner {
    private final DatabaseBackupService service;
    private final DatabaseDumpPort dump;
    private final BackupObjectStore store;
    private final DatabaseBackupConfiguration config;
    private final DatabaseBackupTransactions transactions;
    DatabaseBackupRunner(DatabaseBackupService service, DatabaseDumpPort dump, BackupObjectStore store, DatabaseBackupConfiguration config, DatabaseBackupTransactions transactions) {
        this.service = service; this.dump = dump; this.store = store; this.config = config; this.transactions = transactions;
    }
    void runOnce() {
        if (!service.ready()) return;
        var job = transactions.claim();
        if (job != null) {
            Path archive = null; String stage = "DUMP_FAILED";
            try {
                archive = dump.encryptedDump(); var bytes = Files.size(archive); var hash = BackupArchive.sha256(archive);
                var objectKey = transactions.stage(job.id, job.runToken, store.scopeId(), config.getKeyId(), bytes, hash);
                stage = "UPLOAD_OR_VERIFICATION_FAILED"; store.uploadAndVerify(objectKey, archive, hash);
                stage = "RESULT_UNCONFIRMED"; transactions.complete(job.id, job.runToken);
            } catch (Exception failed) {
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
                transactions.fail(job.id, job.runToken, stage);
            } finally { if (archive != null) { try { Files.deleteIfExists(archive); } catch (java.io.IOException ignored) { warn("加密备份暂存文件等待运维清理"); } } }
        }
        transactions.markExpired(store.scopeId());
        for (var object : transactions.deletions(store.scopeId())) {
            try { store.delete(object.objectKey); transactions.deleted(object.jobId); }
            catch (RuntimeException failed) { warn("私有备份清理暂未完成，将在下一轮重试"); }
        }
    }
    private static void warn(String message) { LoggerFactory.getLogger(DatabaseBackupRunner.class).warn(message); }
}
