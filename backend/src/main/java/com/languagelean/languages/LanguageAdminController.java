package com.languagelean.languages;

import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 管理员语言配置端点，由 /admin 路径的服务端权限规则保护。 */
@RestController
@RequestMapping("/api/v1/admin/languages")
class LanguageAdminController {
    private final LanguageAdminService languages;
    LanguageAdminController(LanguageAdminService languages) { this.languages = languages; }

    /** 包含禁用语言的后台列表。 */
    @GetMapping
    List<LanguageAdminService.View> list() { return languages.list(); }

    /** 通过稳定代码创建或更新配置。 */
    @PostMapping("/{code}")
    LanguageAdminService.View save(@PathVariable String code, @RequestBody LanguageAdminService.Edit edit) {
        return languages.save(code, edit);
    }
}
