package com.languagelean.audio;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 短事务边界；所有云调用在事务外完成，不在网络等待期间占用数据库行锁。 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class AudioTransactions {
    private final AudioAssetRepository assets;
    private final AudioVersionRepository versions;
    /** 注入 ORM 仓储，供外层服务通过代理开启独立事务。 */
    AudioTransactions(AudioAssetRepository assets, AudioVersionRepository versions) {
        this.assets = assets; this.versions = versions;
    }
    /** 首次注册稳定资源；并发创建冲突由外层在已回滚事务后重新读取。 */
    AudioAsset register(UUID id, UUID entryId, String scope, String kind, UUID resourceId) {
        return assets.findById(id).orElseGet(() -> {
            var asset = new AudioAsset(); asset.id = id;
            if (scope.equals("PERSONAL")) asset.personalCustomEntryId = entryId;
            else if (scope.equals("OVERRIDE")) asset.learningItemId = entryId;
            else asset.dictionaryEntryId = entryId;
            asset.contentScope = scope; asset.kind = kind; asset.resourceId = resourceId;
            return assets.saveAndFlush(asset);
        });
    }
    /** 锁内再次检查版本和活跃租约，只有仍需生成时才发放唯一任务令牌。 */
    Claim claim(UUID id, String fingerprint, boolean force, UUID missingVersionId) {
        var asset = assets.lockById(id).orElseThrow();
        var current = asset.currentVersionId == null ? null : versions.findById(asset.currentVersionId).orElseThrow();
        if (!force && current != null && !current.id.equals(missingVersionId) && current.generationFingerprint.equals(fingerprint))
            return new Claim("READY", null, current);
        if (asset.generationToken != null && asset.leaseUntil.isAfter(Instant.now())
                && fingerprint.equals(asset.generationFingerprint)) return new Claim("PENDING", null, current);
        asset.generationToken = UUID.randomUUID(); asset.generationFingerprint = fingerprint;
        asset.leaseUntil = Instant.now().plusSeconds(180); asset.failureCode = null;
        return new Claim("CLAIMED", asset.generationToken, current);
    }
    /** 上传成功且仍持有本任务租约时才追加版本并切换，迟到工作进程不能覆盖新任务。 */
    AudioVersion complete(UUID assetId, UUID token, String fingerprint, TtsSettingsService.Profile profile,
                          String textHash, String fileHash, UUID versionId, String key) {
        var asset = assets.lockById(assetId).orElseThrow();
        if (!token.equals(asset.generationToken)) return null;
        var previous = asset.currentVersionId == null ? null : versions.findById(asset.currentVersionId).orElseThrow();
        var version = new AudioVersion(); version.id = versionId; version.audioAssetId = assetId;
        version.versionNumber = previous == null ? 1 : previous.versionNumber + 1;
        version.provider = profile.provider(); version.model = profile.model(); version.voice = profile.voice();
        version.pronunciationLocale = profile.locale(); version.textHash = textHash;
        version.generationFingerprint = fingerprint; version.fileHash = fileHash;
        version.objectKey = key; version.createdAt = Instant.now();
        version = versions.saveAndFlush(version); asset.currentVersionId = version.id;
        asset.generationToken = null; asset.leaseUntil = null; asset.failureCode = null;
        return version;
    }
    /** 失败释放自身租约，保留旧版本；不能清除另一个已经认领的新任务。 */
    void fail(UUID assetId, UUID token, String code) {
        var asset = assets.lockById(assetId).orElse(null);
        if (asset != null && token.equals(asset.generationToken)) {
            asset.generationToken = null; asset.leaseUntil = null; asset.failureCode = code;
        }
    }
    /** 认领结果与旧快照，离开事务后再检查对象存储。 */
    record Claim(String status, UUID token, AudioVersion current) {}
}
