package com.languagelean.accounts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 账户聚合根的 JPA 持久化入口。 */
interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
}
