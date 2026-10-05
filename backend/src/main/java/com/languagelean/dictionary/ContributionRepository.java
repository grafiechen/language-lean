package com.languagelean.dictionary;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
/** 审核行锁与来源删除清理，个人域只调用清理接口，不获取他人正文。 */
public interface ContributionRepository extends JpaRepository<ContributionSubmission,UUID>, JpaSpecificationExecutor<ContributionSubmission> {
    /** 注销与审核串行；已经完成的公开版本独立保留，私人申请随账号级联删除。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ContributionSubmission s where s.submittedBy = :owner order by s.id")
    List<ContributionSubmission> lockAccountSubmissions(UUID owner);
    /** 本人可读，管理员通过独立路由读取。 */
    Optional<ContributionSubmission> findByIdAndSubmittedBy(UUID id, UUID owner);
    /** 每个来源最多一份待审申请，数据库唯一键继续防止并发重复。 */
    boolean existsByActiveSourceKey(String key);
    /** 同一申请的通过、拒绝及撤回串行化。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from ContributionSubmission s where s.id=:id")
    Optional<ContributionSubmission> lockById(UUID id);
    /** 删除私有来源时彻底清除未公开申请，已公开记录独立保留。 */
    @Modifying @Query("delete from ContributionSubmission s where s.submittedBy=:owner and s.privateEntryId=:source and s.status<>'APPROVED'")
    void deleteUnpublishedPrivate(UUID owner,UUID source);
    /** 删除最后学习关联时同样清除未公开修订申请。 */
    @Modifying @Query("delete from ContributionSubmission s where s.submittedBy=:owner and s.learningItemId=:source and s.status<>'APPROVED'")
    void deleteUnpublishedLearning(UUID owner,UUID source);
}
