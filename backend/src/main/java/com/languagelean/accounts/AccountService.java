package com.languagelean.accounts;

import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 账户用例服务，定义账户写入事务与密码校验边界。 */
@Service
class AccountService {
    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final UserLoginIdentifierRepository identifiers;

    AccountService(UserAccountRepository repository, PasswordEncoder passwordEncoder,
                   UserLoginIdentifierRepository identifiers) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.identifiers = identifiers;
    }

    /**
     * 幂等创建首个管理员。
     *
     * <p>只接收启动环境变量提供的凭据；若登录标识已经存在则不覆盖账号。</p>
     */
    @Transactional
    void createBootstrapAdmin(String username, String email, String rawPassword) {
        var normalizedUsername = UserAccountEntity.normalize(username);
        var normalizedEmail = UserAccountEntity.normalize(email);
        if (identifiers.existsById(normalizedUsername) || identifiers.existsById(normalizedEmail)) return;
        // 仅空库创建首个管理员；已注销的初始化身份不能在重启时由旧环境配置复活。
        if (repository.count() > 0) return;
        // 已有账号不重新初始化；新规则只校验本次真正创建的账号。
        if (!PasswordPolicy.isValid(rawPassword)) {
            throw new IllegalStateException(PasswordPolicy.MESSAGE);
        }
        repository.save(UserAccountEntity.create(username, email, passwordEncoder.encode(rawPassword),
                Set.of(Role.ADMIN, Role.USER)));
    }

    /** 校验当前密码和两次新密码后，以新的随机盐生成并保存哈希。 */
    @Transactional
    UserAccountPrincipal changePassword(java.util.UUID userId, String currentPassword, String newPassword, String confirmation) {
        if (!PasswordPolicy.isValid(newPassword)) {
            throw new ResponseStatusException(BAD_REQUEST, PasswordPolicy.MESSAGE);
        }
        if (!newPassword.equals(confirmation)) {
            throw new ResponseStatusException(BAD_REQUEST, "Password confirmation does not match");
        }
        var account = repository.lockById(userId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Account no longer exists"));
        if (account.getStatus() != AccountStatus.ACTIVE) throw new ResponseStatusException(FORBIDDEN, "Account is disabled");
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new ResponseStatusException(BAD_REQUEST, "Current password is incorrect");
        }
        account.changePassword(passwordEncoder.encode(newPassword));
        // 返回本次改密的凭据快照；提交后不重新读取并误认领并发重置生成的下一代凭据。
        return UserAccountPrincipal.from(account);
    }

    /** 每次读取当前用户时回查数据库，使禁用状态和改密标志及时生效。 */
    @Transactional(readOnly = true)
    AccountProfile profile(java.util.UUID userId) {
        var account = repository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Account no longer exists"));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(FORBIDDEN, "Account is disabled");
        }
        return new AccountProfile(account.getId(), account.getUsername(), account.getEmail(),
                account.getRoles(), account.isMustChangePassword(), account.getNativeLanguage());
    }

    /** 只修改已认证用户自己的译文偏好；代码与页面可选语言保持一致。 */
    @Transactional
    void changeNativeLanguage(java.util.UUID userId, String language) {
        if (language == null || !Set.of("zh-Hans", "zh-Hant", "zh", "en", "ja", "ko", "de", "fr", "es").contains(language))
            throw new ResponseStatusException(BAD_REQUEST, "请选择支持的母语");
        var account = repository.findById(userId).orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Account no longer exists"));
        if (account.getStatus() != AccountStatus.ACTIVE) throw new ResponseStatusException(FORBIDDEN, "Account is disabled");
        account.changeNativeLanguage(language);
    }

    /** 暴露给认证 API 的账户快照，不包含密码哈希。 */
    record AccountProfile(java.util.UUID id, String username, String email,
                          Set<Role> roles, boolean mustChangePassword, String nativeLanguage) {}
}
