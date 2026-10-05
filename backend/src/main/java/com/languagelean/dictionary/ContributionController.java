package com.languagelean.dictionary;
import com.languagelean.accounts.UserAccountPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 本人投稿端点，创建时额外绑定表单所属账号。 */
@RestController @RequestMapping("/api/v1/contributions")
class ContributionController {
    private final ContributionService service;
    ContributionController(ContributionService service) { this.service = service; }
    /** 本人分页记录。 */
    @GetMapping DictionaryService.Results<ContributionService.Row> list(@AuthenticationPrincipal UserAccountPrincipal user,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="0") int page) { return service.list(user.userId(), status, page); }
    /** 本人快照与审核意见。 */
    @GetMapping("/{id}") ContributionService.View detail(@PathVariable UUID id, @AuthenticationPrincipal UserAccountPrincipal user) { return service.detail(user.userId(), id); }
    /** 重复请求幂等，CSRF仍由安全过滤器校验。 */
    @PostMapping ContributionService.View submit(@RequestBody ContributionService.Submit request,
            @RequestHeader("X-Learning-Account") UUID expectedOwner, @AuthenticationPrincipal UserAccountPrincipal user) {
        if (!expectedOwner.equals(user.userId())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "登录账号已变化，请重新打开投稿表单");
        return service.submit(user.userId(), request);
    }
    /** 撤回仅限本人的待审申请。 */
    @PostMapping("/{id}/withdraw") ContributionService.View withdraw(@PathVariable UUID id, @RequestBody ContributionService.Action action,
            @RequestHeader("X-Learning-Account") UUID expectedOwner, @AuthenticationPrincipal UserAccountPrincipal user) {
        if (!expectedOwner.equals(user.userId())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "登录账号已变化，请重新读取申请");
        return service.withdraw(user.userId(), id, action);
    }
}
