package com.languagelean.dictionary;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** 历史版本按所属词条和序号读取，防止跨词条恢复错误内容。 */
interface DictionaryRevisionRepository extends JpaRepository<DictionaryRevision, UUID> {
    /** 仅修改公开贡献身份元信息，不修改正文、来源许可或线性历史版本号。 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update DictionaryRevision r set r.contributorDeleted = true, r.contributedBy = null where r.contributedBy = :owner")
    void anonymizeContributor(UUID owner);
    Optional<DictionaryRevision> findByEntryIdAndRevisionNumber(UUID entryId, int revisionNumber);
    Page<DictionaryRevision> findByEntryIdOrderByRevisionNumberDesc(UUID entryId, Pageable page);
    /** 公开署名仅取最近20次已发布贡献，来源和许可独立于私人账号生命周期。 */
    List<DictionaryRevision> findTop20ByEntryIdAndContributionIdIsNotNullOrderByRevisionNumberDesc(UUID entryId);
}
