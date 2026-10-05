package com.languagelean.operations;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/** JPA汇总投影；不读取私人词条、笔记、答题记录或账号标识等明细。 */
@Repository
class SystemUsageRepository {
    private final EntityManager entities;
    SystemUsageRepository(EntityManager entities) { this.entities = entities; }
    List<Object[]> accounts() { return entities.createQuery("select a.status, count(a) from UserAccountEntity a group by a.status", Object[].class).getResultList(); }
    List<Object[]> dictionary() { return entities.createQuery("select d.status, count(d) from DictionaryEntry d group by d.status", Object[].class).getResultList(); }
    /** 资源数为数据库登记数，不能据此声称云文件仍存在或服务已连通。 */
    Object[] audio(Instant now) {
        return entities.createQuery("""
            select count(a), coalesce(sum(case when a.currentVersionId is not null then 1 else 0 end), 0),
              coalesce(sum(case when a.generationToken is not null and a.leaseUntil > :now then 1 else 0 end), 0),
              coalesce(sum(case when a.failureCode = 'GENERATION_FAILED' then 1 else 0 end), 0)
            from AudioAsset a
            """, Object[].class).setParameter("now", now).getSingleResult();
    }
    long audioVersions() { return entities.createQuery("select count(v) from AudioVersion v", Long.class).getSingleResult(); }
    long cleanup() { return entities.createQuery("select count(c) from AudioObjectCleanup c", Long.class).getSingleResult(); }
    long feedback() { return entities.createQuery("select count(f) from AudioFeedback f where f.status = 'PENDING'", Long.class).getSingleResult(); }
    long contributions() { return entities.createQuery("select count(c) from ContributionSubmission c where c.status = 'PENDING_REVIEW'", Long.class).getSingleResult(); }
    /** UTC半开区间保证月末归属准确，跨月完成的请求仍归属开始月份。 */
    Object[] generation(Instant from, Instant to) {
        return entities.createQuery("""
            select count(u), coalesce(sum(u.inputCharacters), 0), count(u.responseBytes), coalesce(sum(u.responseBytes), 0),
              coalesce(sum(case when u.outcome = 'READY' then 1 else 0 end), 0),
              coalesce(sum(case when u.outcome = 'FAILED' then 1 else 0 end), 0),
              coalesce(sum(case when u.outcome = 'DISCARDED' then 1 else 0 end), 0),
              coalesce(sum(case when u.outcome = 'REQUESTED' then 1 else 0 end), 0)
            from AudioGenerationUsage u where u.requestedAt >= :from and u.requestedAt < :to
            """, Object[].class).setParameter("from", from).setParameter("to", to).getSingleResult();
    }
    Instant firstGeneration() { return entities.createQuery("select min(u.requestedAt) from AudioGenerationUsage u", Instant.class).getSingleResult(); }
}
