package com.languagelean.accounts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.List;

/** 账户聚合根的 JPA 持久化入口。 */
interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
    /** 搜索参数先转义 LIKE 通配符；管理列表不会加载学习内容。 */
    @Query("select a from UserAccountEntity a where (:status is null or a.status = :status) and "
            + "(a.normalizedUsername like :pattern escape '!' or a.normalizedEmail like :pattern escape '!')")
    Page<UserAccountEntity> search(String pattern, AccountStatus status, Pageable pageable);

    /** 按固定顺序锁住全部管理员，串行校验最后一个可用管理员。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from UserAccountEntity a where :role member of a.roles order by a.id")
    List<UserAccountEntity> lockRoleMembers(Role role);

    /** 修改密码、状态和消费重置链接共用账户锁，避免并发覆盖。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from UserAccountEntity a where a.id = :id")
    Optional<UserAccountEntity> lockById(UUID id);
}
