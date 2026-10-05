package com.languagelean.learning;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 个人修改元信息仓储，外部不提供跨账号查询接口。 */
interface PersonalEntryOverrideChangeRepository extends JpaRepository<PersonalEntryOverrideChange, UUID> {}
