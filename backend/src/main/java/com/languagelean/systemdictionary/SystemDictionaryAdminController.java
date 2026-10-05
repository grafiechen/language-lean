package com.languagelean.systemdictionary;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 管理员系统字典端点，由统一的 /admin 安全规则保护。 */
@RestController
@RequestMapping("/api/v1/admin/system-dictionaries")
class SystemDictionaryAdminController {
    private final SystemDictionaryService dictionaries;
    SystemDictionaryAdminController(SystemDictionaryService dictionaries) { this.dictionaries = dictionaries; }

    @GetMapping
    List<SystemDictionaryService.Summary> list() { return dictionaries.list(); }

    @GetMapping("/{code}")
    SystemDictionaryService.View detail(@PathVariable String code) { return dictionaries.detail(code); }

    @PostMapping("/{code}")
    SystemDictionaryService.View save(@PathVariable String code,
                                      @RequestBody SystemDictionaryService.DictionaryEdit edit) {
        return dictionaries.save(code, edit);
    }

    @PostMapping("/{code}/items")
    SystemDictionaryService.View createItem(@PathVariable String code,
                                            @RequestBody SystemDictionaryService.ItemEdit edit) {
        return dictionaries.createItem(code, edit);
    }

    @PostMapping("/{code}/items/{id}")
    SystemDictionaryService.View updateItem(@PathVariable String code, @PathVariable UUID id,
                                            @RequestBody SystemDictionaryService.ItemEdit edit) {
        return dictionaries.updateItem(code, id, edit);
    }
}
