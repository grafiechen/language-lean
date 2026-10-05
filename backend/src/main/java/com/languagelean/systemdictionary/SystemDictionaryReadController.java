package com.languagelean.systemdictionary;
import org.springframework.web.bind.annotation.*;
/** 普通用户只读启用的内容来源选项，不开放配置维护或任意系统字典。 */
@RestController @RequestMapping("/api/v1/system-dictionaries")
class SystemDictionaryReadController {
    private final SystemDictionaryService service;
    SystemDictionaryReadController(SystemDictionaryService service) { this.service = service; }
    /** 私有录入复用后台维护的下拉项，停用选项不用于新选择。 */
    @GetMapping("/CONTENT_SOURCE") SystemDictionaryService.View contentSources() {
        var source = service.detail("CONTENT_SOURCE");
        return new SystemDictionaryService.View(source.code(), source.displayName(), source.description(), source.enabled(), source.version(),
            source.enabled() ? source.items().stream().filter(SystemDictionaryService.ItemView::enabled).toList() : java.util.List.of());
    }
}
