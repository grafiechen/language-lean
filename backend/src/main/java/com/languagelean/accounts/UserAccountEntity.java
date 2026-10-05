package com.languagelean.accounts;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 账户聚合根。
 *
 * <p>展示字段与规范化登录标识分开保存；密码字段只保存带随机盐的单向哈希。
 * 登录标识由 {@link UserLoginIdentifierEntity} 维护全局唯一性。</p>
 */
@Entity
@Table(name = "user_account")
class UserAccountEntity {
    @Id
    private UUID id;
    @Column(nullable = false, length = 80)
    private String username;
    @Column(name = "normalized_username", nullable = false, unique = true, length = 80)
    private String normalizedUsername;
    @Column(nullable = false, length = 320)
    private String email;
    @Column(name = "normalized_email", nullable = false, unique = true, length = 320)
    private String normalizedEmail;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AccountStatus status;
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;
    @Column(name = "native_language", nullable = false, length = 35)
    private String nativeLanguage = "zh-Hans";
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_account_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new LinkedHashSet<>();
    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<UserLoginIdentifierEntity> loginIdentifiers = new LinkedHashSet<>();
    @Version
    private long version;
    @Column(name = "security_version", nullable = false)
    private long securityVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccountEntity() {}

    /** 创建新账户并同时生成用户名、邮箱两个登录标识。 */
    static UserAccountEntity create(String username, String email, String passwordHash, Set<Role> roles) {
        var account = new UserAccountEntity();
        account.id = UUID.randomUUID();
        account.username = username.trim();
        account.normalizedUsername = normalize(username);
        account.email = email.trim();
        account.normalizedEmail = normalize(email);
        account.passwordHash = passwordHash;
        account.status = AccountStatus.ACTIVE;
        account.mustChangePassword = true;
        account.roles.addAll(roles);
        account.loginIdentifiers.add(UserLoginIdentifierEntity.create(account, account.normalizedUsername,
                UserLoginIdentifierEntity.IdentifierType.USERNAME));
        account.loginIdentifiers.add(UserLoginIdentifierEntity.create(account, account.normalizedEmail,
                UserLoginIdentifierEntity.IdentifierType.EMAIL));
        account.createdAt = Instant.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    /** 生成登录查找键；原始展示值仍保存在 username/email 字段。 */
    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    UUID getId() { return id; }
    long getVersion() { return version; }
    long getSecurityVersion() { return securityVersion; }
    Instant getCreatedAt() { return createdAt; }
    /** 禁用仅阻止在线访问，不清除账户的学习数据。 */
    void changeStatus(AccountStatus value) { status = value; securityVersion++; updatedAt = Instant.now(); }
    String getUsername() { return username; }
    String getEmail() { return email; }
    String getPasswordHash() { return passwordHash; }
    AccountStatus getStatus() { return status; }
    boolean isMustChangePassword() { return mustChangePassword; }
    String getNativeLanguage() { return nativeLanguage; }
    /** 更新展示偏好，不创建或重置任何学习记录。 */
    void changeNativeLanguage(String language) { nativeLanguage = language; updatedAt = Instant.now(); }
    Set<Role> getRoles() { return Set.copyOf(roles); }

    /** 保存新密码哈希并清除“需要修改初始密码”标志。 */
    void changePassword(String encodedPassword) {
        passwordHash = encodedPassword;
        securityVersion++;
        mustChangePassword = false;
        updatedAt = Instant.now();
    }
}
