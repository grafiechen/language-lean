package com.languagelean.audio;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
/** 音频身份仓储；行锁在全部工作进程之间串行化认领与切换。 */
interface AudioAssetRepository extends JpaRepository<AudioAsset, UUID> {
    /** 注销只选本人私人/覆盖资产，不回收其他用户仍引用的公共音频。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select a from AudioAsset a where
        (a.contentScope = 'PERSONAL' and a.personalCustomEntryId in (select p.id from PrivateEntry p where p.userId = :owner))
        or (a.contentScope = 'OVERRIDE' and a.learningItemId in (select i.id from UserLearningItem i where i.userId = :owner))
        order by a.id
        """)
    List<AudioAsset> lockAccountAssets(UUID owner);
    List<AudioAsset> findByPersonalCustomEntryId(UUID entryId);
    /** 个人覆盖音频按共享学习身份回收。 */
    List<AudioAsset> findByLearningItemId(UUID itemId);
    /** 备份仅包含本人私人音频、本人覆盖及已引用的公开音频，不包含管理员草稿或他人资源。 */
    @Query("""
        select a from AudioAsset a where
        (a.contentScope = 'PERSONAL' and a.personalCustomEntryId in (select p.id from PrivateEntry p where p.userId = :userId))
        or (a.contentScope = 'OVERRIDE' and a.learningItemId in (select i.id from UserLearningItem i where i.userId = :userId))
        or (a.contentScope = 'PUBLISHED' and a.dictionaryEntryId in (select i.dictionaryEntryId from UserLearningItem i where i.userId = :userId))
        order by a.id
        """)
    List<AudioAsset> backupReferences(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AudioAsset a where a.id = :id")
    Optional<AudioAsset> lockById(UUID id);
}
