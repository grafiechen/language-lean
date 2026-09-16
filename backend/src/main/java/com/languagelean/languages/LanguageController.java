package com.languagelean.languages;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 向未登录客户端发布后台已启用的语言和题型契约。 */
@RestController
@RequestMapping("/api/v1/languages")
class LanguageController {
    private final LanguageService service;
    LanguageController(LanguageService service) { this.service = service; }

    /** 查询全部启用语言；返回 DTO，避免直接序列化 JPA 实体。 */
    @GetMapping
    List<LanguageView> enabledLanguages() {
        return service.enabledLanguages();
    }

    /** 语言配置的 API 表示。 */
    record LanguageView(String code, String displayName, String pronunciationLocale, List<ReviewTypeView> reviewTypes) {}
    /** 语言当前启用的题型及其协议版本。 */
    record ReviewTypeView(String typeId, int contractVersion) {}
}
