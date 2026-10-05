package com.languagelean.audio;

import com.languagelean.accounts.UserAccountPrincipal;
import com.languagelean.dictionary.DictionaryService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/** 管理员反馈队列与生成入口；云调用结束前不把反馈改为已修复。 */
@RestController @RequestMapping("/api/v1/admin/audio-feedback")
class AudioFeedbackAdminController {
    private final AudioFeedbackService service;
    private final AudioService audio;
    /** 注入短事务反馈用例与已有跨进程生成锁。 */
    AudioFeedbackAdminController(AudioFeedbackService service, AudioService audio) { this.service = service; this.audio = audio; }
    /** 状态筛选和分页由 ORM 处理，默认显示待处理项。 */
    @GetMapping DictionaryService.Results<AudioFeedbackService.View> list(@RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "0") int page) { return service.list(null, status, null, null, "", page); }
    /** 读取问题快照和当前公开资源，封禁后不会暴露当前正文。 */
    @GetMapping("/{id}") AudioFeedbackService.Detail detail(@PathVariable UUID id) { return service.detail(id); }
    /** 生成在数据库事务之外完成，沿用原音频版本、失败保留旧音频和租约复查。 */
    @PostMapping("/{id}/regenerate") AudioGenerationPort.Result regenerate(@PathVariable UUID id, @RequestBody Generation request,
            @RequestHeader("X-Learning-Account") UUID expected, @AuthenticationPrincipal UserAccountPrincipal user) {
        account(expected, user); var current = service.prepareGeneration(id, request.version());
        var report = current.report();
        return audio.ensureResource(report.entryId(), AudioService.Scope.PUBLISHED, AudioService.Kind.valueOf(report.kind()), report.resourceId(), user.userId(), true, true);
    }
    /** 人工试听确认后再处理反馈，缺少新音频或失败时返回冲突。 */
    @PostMapping("/{id}/resolve") AudioFeedbackService.View resolve(@PathVariable UUID id, @RequestBody AudioFeedbackService.Action action,
            @RequestHeader("X-Learning-Account") UUID expected, @AuthenticationPrincipal UserAccountPrincipal user) {
        account(expected, user); return service.resolve(user.userId(), id, action);
    }
    /** 当前认证账号须与用户打开表单时的归属一致。 */
    static void account(UUID expected, UserAccountPrincipal user) {
        if (!expected.equals(user.userId())) throw new ResponseStatusException(CONFLICT, "登录账号已变化，请重新打开反馈表单");
    }
    /** 生成仅接受反馈版本，资源身份从服务器反馈记录提取。 */
    record Generation(long version) {}
}
