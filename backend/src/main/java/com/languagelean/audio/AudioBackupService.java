package com.languagelean.audio;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 学习备份的只读音频元数据：记录版本和哈希，不暴露云对象键、凭据或下载地址。 */
@Service
public class AudioBackupService {
    private final AudioAssetRepository assets;
    private final AudioVersionRepository versions;
    AudioBackupService(AudioAssetRepository assets, AudioVersionRepository versions) { this.assets = assets; this.versions = versions; }

    /** 复用外层学习备份的一致快照，分批读取避免大范围IN查询。 */
    @Transactional(readOnly = true)
    public List<Resource> metadata(UUID userId) {
        var allowed = assets.backupReferences(userId); var ids = allowed.stream().map(asset -> asset.id).toList();
        var grouped = new HashMap<UUID, List<Version>>();
        for (int start = 0; start < ids.size(); start += 200) {
            for (var version : versions.findByAudioAssetIdIn(ids.subList(start, Math.min(ids.size(), start + 200)))) {
                grouped.computeIfAbsent(version.audioAssetId, ignored -> new ArrayList<>()).add(new Version(version.id,
                    version.versionNumber, version.provider, version.model, version.voice, version.pronunciationLocale,
                    version.textHash, version.fileHash, version.createdAt));
            }
        }
        return allowed.stream().map(asset -> new Resource(asset.id, asset.dictionaryEntryId, asset.personalCustomEntryId,
            asset.learningItemId, asset.contentScope, asset.kind, asset.resourceId, asset.currentVersionId,
            grouped.getOrDefault(asset.id, List.of()).stream().sorted(Comparator.comparingInt(Version::number)).toList())).toList();
    }
    /** 没有成功版本的资产仍保留身份；导出不会补生成或检查云文件。 */
    public record Resource(UUID id, UUID dictionaryEntryId, UUID personalCustomEntryId, UUID learningItemId,
        String scope, String kind, UUID resourceId, UUID currentVersionId, List<Version> versions) {}
    public record Version(UUID id, int number, String provider, String model, String voice, String locale,
        String textHash, String fileHash, Instant createdAt) {}
}
