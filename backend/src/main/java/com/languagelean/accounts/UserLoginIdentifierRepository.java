package com.languagelean.accounts;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 负责按规范化用户名或邮箱定位唯一账户。 */
interface UserLoginIdentifierRepository extends JpaRepository<UserLoginIdentifierEntity, String> {
    /** 找回密码先取 ID，再按固定顺序锁账户，避免等待锁前加载过期实体。 */
    @Query("select identifier.account.id from UserLoginIdentifierEntity identifier where identifier.normalizedIdentifier = :identifier")
    Optional<java.util.UUID> findAccountIdByNormalizedIdentifier(String identifier);
    /** 一次加载账户和角色，避免认证事务结束后再触发懒加载。 */
    @Query("select distinct account from UserLoginIdentifierEntity identifier "
            + "join identifier.account account left join fetch account.roles "
            + "where identifier.normalizedIdentifier = :identifier")
    Optional<UserAccountEntity> findAccountByNormalizedIdentifier(@Param("identifier") String identifier);
}
