package com.languagelean.accounts;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 账号管理接口，统一由安全链限制 ADMIN 权限，操作人只取当前会话。 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
class AccountAdminController {
    private final AccountAdminService service;
    private final AccountMailPort mail;
    private final AccountMailConfiguration configuration;
    AccountAdminController(AccountAdminService service, AccountMailPort mail, AccountMailConfiguration configuration) {
        this.service = service; this.mail = mail; this.configuration = configuration;
    }
    /** 只返回邮件是否就绪，不暴露主机、用户名或密码配置。 */
    @GetMapping("/mail") MailStatus mail() { return new MailStatus(mail.configured(), mail.configured() && configuration.recoveryConfigured()); }
    @GetMapping AccountAdminService.Results list(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) AccountStatus status, @RequestParam(defaultValue = "0") int page) { return service.list(q, status, page); }
    @PostMapping AccountAdminService.View create(@AuthenticationPrincipal UserAccountPrincipal actor, @RequestBody Create body) {
        return service.create(actor.userId(), body.username(), body.email(), Boolean.TRUE.equals(body.administrator()));
    }
    @PostMapping("/{id}/status") AccountAdminService.View status(@AuthenticationPrincipal UserAccountPrincipal actor,
            @PathVariable UUID id, @RequestBody Status body) { return service.changeStatus(actor.userId(), id, body.version(), body.status()); }
    record Create(String username, String email, Boolean administrator) {}
    record Status(long version, AccountStatus status) {}
    record MailStatus(boolean configured, boolean recoveryConfigured) {}
}
