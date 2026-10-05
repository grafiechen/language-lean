package com.languagelean.accounts;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 拦截已禁用账号和改密前的会话；不删除浏览器离线学习缓存。 */
@Component
public class AccountSessionFilter extends OncePerRequestFilter {
    private final AccountSessionService accounts;
    AccountSessionFilter(AccountSessionService accounts) { this.accounts = accounts; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return java.util.Set.of("/api/v1/auth/csrf", "/actuator/health", "/api/v1/languages", "/error",
                "/api/v1/auth/password-recovery", "/api/v1/auth/password-reset").contains(request.getServletPath());
    }
    /** Spring Security 已加载会话后，授权之前重新验证账号和凭据。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserAccountPrincipal old) {
            var current = accounts.principal(old.userId());
            if (current == null || !current.enabled() || current.securityVersion() != old.securityVersion() || !current.password().equals(old.password())) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                response.setStatus(401); response.setContentType("application/json");
                response.getWriter().write(current == null
                        ? "{\"code\":\"ACCOUNT_DELETED\",\"accountId\":\"" + old.userId() + "\",\"detail\":\"账号已注销\"}"
                        : "{\"code\":\"ACCOUNT_SESSION_EXPIRED\",\"detail\":\"账号状态或密码已变更，请重新登录\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
