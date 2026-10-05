package com.languagelean.audio;
import java.util.*;
import org.springframework.web.bind.annotation.*;
/** 管理员 TTS 设置与错误发音的重新生成入口。 */
@RestController @RequestMapping("/api/v1/admin/audio")
class AudioAdminController {
    private final AudioService audio;
    private final TtsSettingsService settings;
    /** 所有路由由管理员安全规则保护。 */
    AudioAdminController(AudioService audio, TtsSettingsService settings) { this.audio = audio; this.settings = settings; }
    /** 返回声音设置和部署配置状态，不返回凭据。 */
    @GetMapping("/settings") View list() { return new View(settings.list(), audio.availability()); }
    /** 更新指定语言的声音和生成开关。 */
    @PostMapping("/settings/{language}") TtsSettingsService.View save(@PathVariable String language, @RequestBody TtsSettingsService.Edit edit) {
        return settings.save(language, edit);
    }
    /** 管理员确认读音有误后创建新版本，失败时不覆盖旧版本。 */
    @PostMapping("/entries/{entryId}/resources/{resourceId}/regenerate")
    AudioGenerationPort.Result regenerate(@PathVariable UUID entryId, @PathVariable UUID resourceId,
            @RequestParam AudioService.Kind kind, @RequestParam(defaultValue = "PUBLISHED") AudioService.Scope scope,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.languagelean.accounts.UserAccountPrincipal user) {
        return audio.ensureResource(entryId, scope, kind, resourceId, user.userId(), true, true);
    }
    /** 统一后台响应结构。 */
    record View(List<TtsSettingsService.View> languages, AudioService.Availability availability) {}
}
