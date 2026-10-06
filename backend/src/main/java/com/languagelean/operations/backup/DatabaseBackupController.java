package com.languagelean.operations.backup;
import java.util.UUID;
import com.languagelean.accounts.UserAccountPrincipal;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** ADMIN和CSRF由安全链验证；不提供私有归档下载及恢复生产数据库路由。 */
@RestController @RequestMapping("/api/v1/admin/system/backups")
class DatabaseBackupController {
    private final DatabaseBackupService service;
    DatabaseBackupController(DatabaseBackupService service) { this.service = service; }
    @GetMapping ResponseEntity<DatabaseBackupService.Overview> overview() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview()); }
    @PostMapping ResponseEntity<DatabaseBackupService.Job> request(@RequestBody Request body, @AuthenticationPrincipal UserAccountPrincipal actor) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.request(body == null ? null : body.requestId(), actor.userId()));
    }
    record Request(UUID requestId) {}
}
