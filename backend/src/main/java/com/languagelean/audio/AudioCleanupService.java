package com.languagelean.audio;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 将数据库删除与云文件删除隔离，失败继续重试，不能因云异常恢复私人内容。 */
@Service
public class AudioCleanupService {
    private final AudioAssetRepository assets;
    private final AudioVersionRepository versions;
    private final AudioObjectCleanupRepository tasks;
    private final AudioObjectStore store;
    AudioCleanupService(AudioAssetRepository assets, AudioVersionRepository versions, AudioObjectCleanupRepository tasks, AudioObjectStore store) {
        this.assets = assets; this.versions = versions; this.tasks = tasks; this.store = store;
    }
    /** 源进度/词条已锁定后，捕获本人全部历史云文件，并撤销尚未完成的生成租约。 */
    @Transactional
    public void scheduleAccount(UUID owner) {
        for (var asset : assets.lockAccountAssets(owner)) {
            for (var version : versions.findByAudioAssetId(asset.id)) tasks.save(AudioObjectCleanup.of(version.objectKey));
            asset.currentVersionId = null; asset.generationToken = null; asset.leaseUntil = null;
        }
        assets.flush();
    }
    /** 与词条删除同事务保存全部版本对象键，业务调用前已经校验归属。 */
    @Transactional
    public void scheduleEntry(UUID entryId) {
        for (var candidate : assets.findByPersonalCustomEntryId(entryId)) {
            var asset = assets.lockById(candidate.id).orElseThrow();
            for (var version : versions.findByAudioAssetId(asset.id)) tasks.save(AudioObjectCleanup.of(version.objectKey));
        }
    }
    /** 最后关联删除前捕获本人覆盖音频的全部版本键。 */
    @Transactional
    public void scheduleLearningItem(UUID itemId) {
        for (var candidate : assets.findByLearningItemId(itemId)) {
            var asset = assets.lockById(candidate.id).orElseThrow();
            for (var version : versions.findByAudioAssetId(asset.id)) tasks.save(AudioObjectCleanup.of(version.objectKey));
        }
    }
    /** 恢复继承时清除该类型私人音频身份，同时登记云文件清理。 */
    @Transactional
    public void removeLearningAudio(UUID itemId, String kind) {
        for (var candidate : assets.findByLearningItemId(itemId)) if (candidate.kind.equals(kind)) {
            var asset = assets.lockById(candidate.id).orElseThrow();
            for (var version : versions.findByAudioAssetId(asset.id)) tasks.save(AudioObjectCleanup.of(version.objectKey));
            // 先断开当前版本和租约，再级联删除，避免环形版本外键及迟到任务切换。
            asset.currentVersionId = null; asset.generationToken = null; asset.leaseUntil = null;
            assets.saveAndFlush(asset);
            assets.delete(asset);
        }
        assets.flush();
    }
    /** 删除与云生成发生竞态时，未切换成功的新上传对象也应清除。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void orphan(String key) { tasks.saveAndFlush(AudioObjectCleanup.of(key)); }
    /** 云调用不持有数据库事务；成功才撤销任务，重复删除是幂等的。 */
    public int drain() {
        if (!store.configured()) return 0;
        int completed = 0;
        for (var task : tasks.findTop16ByOrderByCreatedAtAscIdAsc()) {
            try { store.delete(task.objectKey); tasks.deleteById(task.id); completed++; }
            catch (RuntimeException failed) { /* 网络、权限或数据库失败均保留任务，不输出上游响应。 */ }
        }
        return completed;
    }
}
