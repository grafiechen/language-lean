package com.languagelean.operations.backup;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
/** 备份状态全部由JPA维护，行锁只用于短事务。 */
interface DatabaseBackupJobRepository extends JpaRepository<DatabaseBackupJob, UUID> {
    Optional<DatabaseBackupJob> findFirstByStateOrderByCreatedAtAsc(String state);
    Optional<DatabaseBackupJob> findFirstByStateOrderByFinishedAtDesc(String state);
    List<DatabaseBackupJob> findAllByOrderByCreatedAtDesc(Pageable page);
}
interface DatabaseBackupControlRepository extends JpaRepository<DatabaseBackupControl, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from DatabaseBackupControl c where c.id = 'DATABASE'")
    DatabaseBackupControl lockControl();
}
interface DatabaseBackupObjectRepository extends JpaRepository<DatabaseBackupObject, UUID> {
    Optional<DatabaseBackupObject> findFirstByStateAndStorageScopeOrderByVerifiedAtDesc(String state, String storageScope);
    List<DatabaseBackupObject> findByStateAndStorageScope(String state, String storageScope, Pageable page);
    @Query("select o from DatabaseBackupObject o where o.state = 'VERIFIED' and o.storageScope = :scope and o.verifiedAt < :cutoff and o.jobId <> :latest")
    List<DatabaseBackupObject> expired(@Param("scope") String scope, @Param("cutoff") Instant cutoff, @Param("latest") UUID latest, Pageable page);
}
