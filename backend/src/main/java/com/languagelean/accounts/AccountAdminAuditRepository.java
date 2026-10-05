package com.languagelean.accounts;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 账号审计的 ORM 持久化入口。 */
interface AccountAdminAuditRepository extends JpaRepository<AccountAdminAudit, UUID> {}
