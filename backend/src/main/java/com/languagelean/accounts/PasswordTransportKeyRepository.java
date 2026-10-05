package com.languagelean.accounts;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

/** 行锁保证并发请求也只能消费一次，禁止在控制器中直接执行 SQL。 */
interface PasswordTransportKeyRepository extends JpaRepository<PasswordTransportKey, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from PasswordTransportKey k where k.id = :id")
    Optional<PasswordTransportKey> lockById(@Param("id") UUID id);
    long countBySessionHashAndExpiresAtAfter(String sessionHash, Instant now);
    @Modifying @Query("delete from PasswordTransportKey k where k.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
