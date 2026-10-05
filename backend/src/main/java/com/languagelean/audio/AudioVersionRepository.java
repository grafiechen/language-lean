package com.languagelean.audio;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/** 不可变音频快照仓储。 */
interface AudioVersionRepository extends JpaRepository<AudioVersion, UUID> {
    java.util.List<AudioVersion> findByAudioAssetId(UUID assetId);
    /** 备份分批加载已获授权资产的全部版本，不访问对象存储。 */
    java.util.List<AudioVersion> findByAudioAssetIdIn(java.util.Collection<UUID> assetIds);
}
