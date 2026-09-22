package com.languagelean.dictionary;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 已登录用户的公开基准词典查询，不暴露管理员草稿与发布审计。 */
@RestController
@RequestMapping("/api/v1/dictionary")
class DictionaryController {
    private final DictionaryService dictionary;
    DictionaryController(DictionaryService dictionary) { this.dictionary = dictionary; }
    /** 根据写法搜索已发布词条，保留封禁提示。 */
    @GetMapping
    DictionaryService.Results<DictionaryService.Row> list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String language, @RequestParam(defaultValue = "0") int page) {
        return dictionary.list(false, q, language, page);
    }
    /** 详情只返回当前公开版本。 */
    @GetMapping("/{id}")
    DictionaryService.PublicView detail(@PathVariable UUID id) { return dictionary.detail(id); }
}
