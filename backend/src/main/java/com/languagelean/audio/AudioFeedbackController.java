package com.languagelean.audio;

import com.languagelean.accounts.UserAccountPrincipal;
import com.languagelean.dictionary.DictionaryService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 普通用户仅提交和查看自己的公共音频反馈。 */
@RestController @RequestMapping("/api/v1/audio-feedback")
class AudioFeedbackController {
    private final AudioFeedbackService service;
    /** 注入只读公共内容的反馈用例。 */
    AudioFeedbackController(AudioFeedbackService service) { this.service = service; }
    /** 分页查看本人反馈，可限定当前词条的某一读音或例句。 */
    @GetMapping DictionaryService.Results<AudioFeedbackService.View> list(@RequestParam(required = false) UUID entryId,
            @RequestParam(required = false) UUID resourceId, @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "0") int page, @AuthenticationPrincipal UserAccountPrincipal user) {
        return service.list(user.userId(), "", entryId, resourceId, kind, page);
    }
    /** 表单账户与 Cookie 会话必须一致，避免另一标签切换账号后误归属。 */
    @PostMapping AudioFeedbackService.View submit(@RequestBody AudioFeedbackService.Submit request,
            @RequestHeader("X-Learning-Account") UUID expected, @AuthenticationPrincipal UserAccountPrincipal user) {
        AudioFeedbackAdminController.account(expected, user); return service.submit(user.userId(), request);
    }
}
