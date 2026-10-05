package com.languagelean.accounts;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** 当前会话相关接口：CSRF 初始化、当前用户查询和登录态改密。 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AccountService accounts;

    AuthController(AccountService accounts) {
        this.accounts = accounts;
    }
    /** 返回前端发起写请求时必须携带的 CSRF 请求头名称和值。 */
    @GetMapping("/csrf")
    CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    /** 返回当前认证账户的最新数据库快照，不返回密码哈希。 */
    @GetMapping("/me")
    CurrentUserView me(@AuthenticationPrincipal UserAccountPrincipal principal) {
        var profile = accounts.profile(principal.userId());
        Set<String> roles = profile.roles().stream()
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
        return new CurrentUserView(profile.id(), profile.username(), profile.email(),
                roles, profile.mustChangePassword(), profile.nativeLanguage());
    }

    /** 修改当前账户密码；身份从服务端会话获取，不接受请求体指定用户。 */
    @PostMapping("/password")
    void changePassword(@AuthenticationPrincipal UserAccountPrincipal principal,
                        @RequestBody ChangePasswordRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
        var current = accounts.changePassword(principal.userId(), request.currentPassword(),
                request.newPassword(), request.confirmation());
        // 改密事务提交后只刷新当前会话；其他设备保存的旧凭据会被过滤器拦截。
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current, null, current.getAuthorities()));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, servletRequest, response);
    }

    record CsrfView(String headerName, String token) {}
    /** 母语设置仅影响译文，身份由会话确定，不能指定其他账号。 */
    @PostMapping("/preferences")
    void preferences(@AuthenticationPrincipal UserAccountPrincipal principal, @RequestBody Preferences request) {
        accounts.changeNativeLanguage(principal.userId(), request.nativeLanguage());
    }
    record Preferences(String nativeLanguage) {}
    record CurrentUserView(java.util.UUID id, String username, String email,
                           Set<String> roles, boolean mustChangePassword, String nativeLanguage) {}
    record ChangePasswordRequest(String currentPassword, String newPassword, String confirmation) {}
}
