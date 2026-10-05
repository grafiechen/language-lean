package com.languagelean.learning;

import com.languagelean.accounts.UserAccountPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 当前认证用户的个人有效内容接口；不接受客户端自报的账户身份。 */
@RestController
@RequestMapping("/api/v1/learning/items/{itemId}/content")
class PersonalContentController {
    private final PersonalContentService content;
    PersonalContentController(PersonalContentService content) { this.content = content; }
    /** 普通用户与管理员都只能读取自己的个人内容。 */
    @GetMapping
    PersonalContentService.View detail(@PathVariable UUID itemId, @AuthenticationPrincipal UserAccountPrincipal user) {
        return content.detail(user.userId(), itemId);
    }
    /** 所有更新继续受认证与 CSRF 保护，旧版本返回冲突且不覆盖。 */
    @PutMapping
    PersonalContentService.View save(@PathVariable UUID itemId, @RequestBody PersonalContentService.Save request,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return content.save(user.userId(), itemId, request);
    }
}
