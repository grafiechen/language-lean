package com.languagelean.languages;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** 语言配置的 JPA 查询入口。 */
interface LanguageRepository extends JpaRepository<LanguageEntity, String> {
    /** 一次加载启用语言和题型，避免 Controller 层产生 N+1 查询。 */
    @EntityGraph(attributePaths = "reviewTypes")
    List<LanguageEntity> findByEnabledTrueOrderByCodeAsc();
}
