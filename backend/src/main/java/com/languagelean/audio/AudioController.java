package com.languagelean.audio;
import com.languagelean.accounts.UserAccountPrincipal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 当前用户可访问的读音和例句音频 API。 */
@RestController @RequestMapping("/api/v1/audio")
class AudioController {
    private final AudioService audio;
    /** 注入统一音频服务。 */
    AudioController(AudioService audio) { this.audio = audio; }
    /** 点击、详情补齐和训练资源准备复用同一生成入口。 */
    @PostMapping("/entries/{entryId}/resources/{resourceId}/ensure")
    AudioGenerationPort.Result ensure(@PathVariable UUID entryId, @PathVariable UUID resourceId,
            @RequestParam AudioService.Kind kind, @RequestParam(defaultValue = "PUBLISHED") AudioService.Scope scope,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return audio.ensureResource(entryId, scope, kind, resourceId, user.userId(), admin(user), false);
    }
    /** 本人修正读音后可重新生成个人资源，不能用此入口强制生成公共音频。 */
    @PostMapping("/entries/{entryId}/resources/{resourceId}/regenerate")
    AudioGenerationPort.Result regenerate(@PathVariable UUID entryId, @PathVariable UUID resourceId,
            @RequestParam AudioService.Kind kind, @RequestParam AudioService.Scope scope,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        if (scope != AudioService.Scope.PERSONAL && scope != AudioService.Scope.OVERRIDE) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "此入口仅用于本人的个人音频");
        return audio.ensureResource(entryId, scope, kind, resourceId, user.userId(), false, true);
    }
    /** 文件不进入公共浏览器缓存，离线模块按版本和账号明确缓存。 */
    @GetMapping("/versions/{versionId}")
    ResponseEntity<byte[]> content(@PathVariable UUID versionId, @AuthenticationPrincipal UserAccountPrincipal user) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("audio/mpeg"))
                .cacheControl(CacheControl.noStore()).body(audio.content(versionId, user.userId(), admin(user)));
    }
    /** 从认证角色提取权限，不能接收客户端的管理员布尔值。 */
    static boolean admin(UserAccountPrincipal user) {
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
