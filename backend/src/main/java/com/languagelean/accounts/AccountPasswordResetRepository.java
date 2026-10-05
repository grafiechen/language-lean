package com.languagelean.accounts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 一次性重置凭据的 ORM 持久化入口。 */
interface AccountPasswordResetRepository extends JpaRepository<AccountPasswordReset, String> {
    Optional<AccountPasswordReset> findFirstByAccountIdOrderByCreatedAtDesc(UUID accountId);
    @org.springframework.transaction.annotation.Transactional
    void deleteByAccountId(UUID accountId);
}
