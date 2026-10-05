package com.languagelean.learning;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/** 内部审计仓储，不开放跨账号查询。 */
interface PrivateEntryChangeRepository extends JpaRepository<PrivateEntryChange, UUID> {}
