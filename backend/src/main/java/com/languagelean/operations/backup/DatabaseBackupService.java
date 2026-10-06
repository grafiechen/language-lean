package com.languagelean.operations.backup;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 只向管理员展示状态和脱敏元数据；禁止下载、提交凭据或指定转储数据库。 */
@Service
class DatabaseBackupService {
    private final DatabaseBackupConfiguration config;
    private final DatabaseDumpPort dump;
    private final BackupObjectStore store;
    private final DatabaseBackupJobRepository jobs;
    private final DatabaseBackupObjectRepository objects;
    private final DatabaseBackupTransactions transactions;
    DatabaseBackupService(DatabaseBackupConfiguration config, DatabaseDumpPort dump, BackupObjectStore store,
                          DatabaseBackupJobRepository jobs, DatabaseBackupObjectRepository objects, DatabaseBackupTransactions transactions) {
        this.config = config; this.dump = dump; this.store = store; this.jobs = jobs; this.objects = objects; this.transactions = transactions;
    }
    boolean ready() { return config.isEnabled() && config.policyValid() && config.encryptionConfigured() && dump.configured() && store.configured(); }
    @Transactional(readOnly = true)
    Overview overview() {
        return new Overview(config.isEnabled(), ready(), config.encryptionConfigured(), dump.configured(), store.configured(), config.policyValid(),
            config.getIntervalHours(), config.getRetentionDays(), jobs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 30)).stream().map(this::view).toList());
    }
    Job request(UUID id, UUID actor) {
        if (id == null) throw new ResponseStatusException(BAD_REQUEST, "缺少备份请求标识");
        if (!ready()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "服务器备份尚未启用或配置不完整");
        return view(transactions.request(id, actor));
    }
    private Job view(DatabaseBackupJob job) {
        var object = objects.findById(job.id).orElse(null);
        return new Job(job.id, job.triggerKind, job.state, job.createdAt, job.startedAt, job.finishedAt, job.errorCode,
            object == null ? null : object.encryptedBytes, object == null ? null : object.encryptionKeyId, object == null ? null : object.state);
    }
    record Overview(boolean enabled, boolean ready, boolean encryptionConfigured, boolean databaseDumpConfigured, boolean storageConfigured,
                    boolean policyValid, int intervalHours, int retentionDays, List<Job> jobs) {}
    record Job(UUID id, String triggerKind, String state, Instant createdAt, Instant startedAt, Instant finishedAt, String errorCode,
               Long encryptedBytes, String encryptionKeyId, String archiveState) {}
}
