package com.languagelean.accounts;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将用户名或邮箱登录查询适配为 Spring Security 的 UserDetailsService。 */
@Service
class AccountUserDetailsService implements UserDetailsService {
    private final UserLoginIdentifierRepository identifiers;
    AccountUserDetailsService(UserLoginIdentifierRepository identifiers) { this.identifiers = identifiers; }

    /** 规范化用户输入，并通过全局登录标识表加载账户及角色。 */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) {
        var normalized = UserAccountEntity.normalize(identifier);
        return identifiers.findAccountByNormalizedIdentifier(normalized)
                .map(UserAccountPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
