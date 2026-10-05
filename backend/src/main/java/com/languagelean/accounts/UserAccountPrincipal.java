package com.languagelean.accounts;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 写入服务端会话的认证主体。
 *
 * <p>userId 是业务接口和本地缓存的稳定归属；邮箱、用户名不作为业务外键。</p>
 */
public record UserAccountPrincipal(
        UUID userId, String username, String email, String password,
        boolean enabled, boolean mustChangePassword, long securityVersion, Collection<? extends GrantedAuthority> authorities)
        implements UserDetails {

    /** 将数据库角色转换为 Spring Security 的 ROLE_* 权限。 */
    static UserAccountPrincipal from(UserAccountEntity account) {
        var authorities = account.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        return new UserAccountPrincipal(account.getId(), account.getUsername(), account.getEmail(),
                account.getPasswordHash(), account.getStatus() == AccountStatus.ACTIVE,
                account.isMustChangePassword(), account.getSecurityVersion(), authorities);
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return enabled; }
}
