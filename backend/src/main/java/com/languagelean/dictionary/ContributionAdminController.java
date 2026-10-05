package com.languagelean.dictionary;
import com.languagelean.accounts.UserAccountPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 贡献审核入口，/admin权限由安全配置统一限制。 */
@RestController @RequestMapping("/api/v1/admin/contributions")
class ContributionAdminController {
    private final ContributionService service;
    ContributionAdminController(ContributionService service) { this.service = service; }
    /** 按审核状态分页读取。 */
    @GetMapping DictionaryService.Results<ContributionService.Row> list(@RequestParam(defaultValue="PENDING_REVIEW") String status,
            @RequestParam(defaultValue="0") int page) { return service.list(null, status, page); }
    /** 只读取用户明确提交的快照及当时公开基准。 */
    @GetMapping("/{id}") ContributionService.View detail(@PathVariable UUID id) { return service.detail(null, id); }
    /** 通过并立即公开，事务中追加词典历史。 */
    @PostMapping("/{id}/approve") ContributionService.View approve(@PathVariable UUID id, @RequestBody ContributionService.Action action,
            @RequestHeader("X-Learning-Account") UUID expectedReviewer, @AuthenticationPrincipal UserAccountPrincipal user) {
        reviewer(expectedReviewer, user); return service.approve(user.userId(), id, action);
    }
    /** 拒绝需填写原因，公开词典保持原版本。 */
    @PostMapping("/{id}/reject") ContributionService.View reject(@PathVariable UUID id, @RequestBody ContributionService.Action action,
            @RequestHeader("X-Learning-Account") UUID expectedReviewer, @AuthenticationPrincipal UserAccountPrincipal user) {
        reviewer(expectedReviewer, user); return service.reject(user.userId(), id, action);
    }
    /** 审核操作绑定管理员表单账号，避免另一标签切换会话后错误署名。 */
    private void reviewer(UUID expected, UserAccountPrincipal user) {
        if (!expected.equals(user.userId())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "登录账号已变化，请重新读取申请");
    }
}
