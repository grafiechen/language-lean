package com.languagelean.operations;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理员用量接口；ADMIN权限由安全链统一验证，禁止缓存账号数量及运行状态。 */
@RestController @RequestMapping("/api/v1/admin/system/usage")
class SystemUsageController {
    private final SystemUsageService service;
    SystemUsageController(SystemUsageService service) { this.service = service; }
    @GetMapping ResponseEntity<SystemUsageService.View> overview(@RequestParam(required = false) String month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview(month));
    }
}
