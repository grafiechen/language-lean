package com.languagelean.systemdictionary;

import org.springframework.data.jpa.repository.JpaRepository;

/** 系统字典定义的 JPA 查询入口。 */
interface SystemDictionaryRepository extends JpaRepository<SystemDictionaryEntity, String> {}
