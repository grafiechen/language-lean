package com.languagelean.audio;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/** 仅内部记录，管理员接口只返回按月汇总，不开放逐次记录查询。 */
interface AudioGenerationUsageRepository extends JpaRepository<AudioGenerationUsage, UUID> {}
