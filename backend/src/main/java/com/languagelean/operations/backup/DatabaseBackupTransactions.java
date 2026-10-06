package com.languagelean.operations.backup;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
/** 短事务认领、确认和清理，不能在持锁时执行转储或云请求。 */
@Service @Transactional(propagation = Propagation.REQUIRES_NEW)
class DatabaseBackupTransactions {
    private final DatabaseBackupJobRepository jobs;
    private final DatabaseBackupControlRepository controls;
    private final DatabaseBackupObjectRepository objects;
    private final DatabaseBackupConfiguration config;
    DatabaseBackupTransactions(DatabaseBackupJobRepository jobs, DatabaseBackupControlRepository controls, DatabaseBackupObjectRepository objects, DatabaseBackupConfiguration config) {
        this.jobs = jobs; this.controls = controls; this.objects = objects; this.config = config;
    }
    DatabaseBackupJob request(UUID id, UUID actor) {
        var control = controls.lockControl(); reap(control);
        var existing = jobs.findById(id); if (existing.isPresent()) return existing.get();
        if (control.activeJob != null) throw new ResponseStatusException(CONFLICT, "已有备份正在执行");
        var queued = jobs.findFirstByStateOrderByCreatedAtAsc("QUEUED"); if (queued.isPresent()) return queued.get();
        return jobs.saveAndFlush(DatabaseBackupJob.create(id, actor));
    }
    DatabaseBackupJob claim() {
        var control = controls.lockControl(); reap(control); if (control.activeJob != null) return null;
        var job = jobs.findFirstByStateOrderByCreatedAtAsc("QUEUED").orElse(null); var now = Instant.now();
        if (job == null && config.getIntervalHours() > 0 && (control.nextScheduledAt == null || !now.isBefore(control.nextScheduledAt))) {
            job = jobs.save(DatabaseBackupJob.create(UUID.randomUUID(), null)); control.nextScheduledAt = now.plusSeconds(config.getIntervalHours() * 3600L);
        }
        if (job == null) return null;
        job.state = "RUNNING"; job.startedAt = now; job.runToken = UUID.randomUUID();
        control.activeJob = job.id; control.leaseUntil = now.plusSeconds(config.getTimeoutSeconds() + 1800L);
        jobs.flush(); return job;
    }
    /** 在上传前记录确定的对象键，故障和进程中断也能排队清理孤立文件。 */
    String stage(UUID id, UUID token, String scope, String keyId, long bytes, String hash) {
        var control = controls.lockControl(); var job = require(control, id, token);
        var object = new DatabaseBackupObject(); object.jobId = id;
        object.objectKey = "database-backups/" + job.createdAt.atZone(ZoneOffset.UTC).toLocalDate() + "/" + id + ".llbackup";
        object.storageScope = scope; object.encryptionKeyId = keyId; object.encryptedBytes = bytes; object.sha256 = hash; object.state = "UPLOADING";
        objects.saveAndFlush(object); return object.objectKey;
    }
    void complete(UUID id, UUID token) {
        var control = controls.lockControl(); var job = require(control, id, token); var object = objects.findById(id).orElseThrow();
        job.state = "SUCCESS"; job.finishedAt = Instant.now(); job.runToken = null;
        object.state = "VERIFIED"; object.verifiedAt = job.finishedAt; control.activeJob = null; control.leaseUntil = null;
    }
    void fail(UUID id, UUID token, String code) {
        var control = controls.lockControl(); var job = jobs.findById(id).orElse(null);
        // 过期工作者的上传可能比另一实例的首次清理更晚结束，再次排队清理其固定对象。
        if (job != null && "FAILED".equals(job.state)) {
            objects.findById(id).ifPresent(object -> object.state = "DELETE_PENDING"); return;
        }
        if (job == null || !"RUNNING".equals(job.state) || !Objects.equals(token, job.runToken)) return;
        failed(job, code); if (id.equals(control.activeJob)) { control.activeJob = null; control.leaseUntil = null; }
    }
    private void reap(DatabaseBackupControl control) {
        if (control.activeJob != null && (control.leaseUntil == null || !Instant.now().isBefore(control.leaseUntil))) {
            jobs.findById(control.activeJob).ifPresent(job -> failed(job, "WORKER_INTERRUPTED")); control.activeJob = null; control.leaseUntil = null;
        }
    }
    private void failed(DatabaseBackupJob job, String code) {
        job.state = "FAILED"; job.finishedAt = Instant.now(); job.errorCode = code; job.runToken = null;
        objects.findById(job.id).ifPresent(object -> { if (!"DELETED".equals(object.state)) object.state = "DELETE_PENDING"; });
    }
    private DatabaseBackupJob require(DatabaseBackupControl control, UUID id, UUID token) {
        var job = jobs.findById(id).orElseThrow();
        if (!id.equals(control.activeJob) || !Objects.equals(token, job.runToken) || !"RUNNING".equals(job.state)
                || control.leaseUntil == null || !Instant.now().isBefore(control.leaseUntil)) throw new IllegalStateException("BACKUP_LEASE_EXPIRED");
        return job;
    }
    /** 只删除已验证、超保留期且有更新成功备份的对象；0为不自动删除。 */
    void markExpired(String scope) {
        if (config.getRetentionDays() <= 0) return;
        var control = controls.lockControl(); var latest = objects.findFirstByStateAndStorageScopeOrderByVerifiedAtDesc("VERIFIED", scope).orElse(null);
        if (latest == null) return;
        objects.expired(scope, Instant.now().minusSeconds(config.getRetentionDays() * 86400L), latest.jobId, PageRequest.of(0, 20)).forEach(object -> object.state = "DELETE_PENDING");
    }
    @Transactional(readOnly = true) List<DatabaseBackupObject> deletions(String scope) { return objects.findByStateAndStorageScope("DELETE_PENDING", scope, PageRequest.of(0, 8)); }
    void deleted(UUID id) {
        var control = controls.lockControl(); var object = objects.findById(id).orElse(null);
        if (object != null && "DELETE_PENDING".equals(object.state)) { object.state = "DELETED"; object.deletedAt = Instant.now(); }
    }
}
