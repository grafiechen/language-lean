package com.languagelean.accounts;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 注销路由均受 CSRF 和会话保护，管理员删除他人的能力位于专用 ADMIN 路由。 */
@RestController
class AccountClosureController {
    private final AccountClosureService service;
    AccountClosureController(AccountClosureService service) { this.service = service; }
    @PostMapping("/api/v1/auth/close-account")
    void closeSelf(@AuthenticationPrincipal UserAccountPrincipal actor, @RequestBody Self request, HttpServletRequest servlet) {
        if (!actor.userId().equals(request.accountId())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "登录账号已变化，请刷新后重试");
        service.closeSelf(actor.userId(), request.password(), request.confirmation());
        SecurityContextHolder.clearContext();
        var session = servlet.getSession(false); if (session != null) session.invalidate();
    }
    @PostMapping("/api/v1/admin/accounts/{id}/delete")
    void delete(@AuthenticationPrincipal UserAccountPrincipal actor, @PathVariable UUID id, @RequestBody Admin request) {
        if (!actor.userId().equals(request.actorId())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "登录账号已变化，请刷新后重试");
        service.closeByAdmin(actor.userId(), id, request.version(), request.password(), request.confirmation());
    }
    record Self(UUID accountId, String password, String confirmation) {}
    record Admin(UUID actorId, Long version, String password, String confirmation) {}
}
