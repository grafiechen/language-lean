package com.languagelean.learning;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 内部仓储；调用方必须先校验所属学习条目，不能直接按外部 ID 暴露内容。 */
interface PersonalEntryOverrideRepository extends JpaRepository<PersonalEntryOverride, UUID> {}
