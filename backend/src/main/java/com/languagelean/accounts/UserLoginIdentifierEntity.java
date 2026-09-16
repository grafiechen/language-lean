package com.languagelean.accounts;

import jakarta.persistence.*;

/**
 * 用户名或邮箱的全局唯一登录入口。
 *
 * <p>独立表可以阻止某个账户的用户名与另一个账户的邮箱相同。</p>
 */
@Entity
@Table(name = "user_login_identifier")
class UserLoginIdentifierEntity {
    enum IdentifierType { USERNAME, EMAIL }

    @Id
    @Column(name = "normalized_identifier", length = 320)
    private String normalizedIdentifier;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccountEntity account;
    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 24)
    private IdentifierType identifierType;

    protected UserLoginIdentifierEntity() {}
    /** 为账户创建一个已经规范化的登录标识。 */
    static UserLoginIdentifierEntity create(UserAccountEntity account, String identifier, IdentifierType type) {
        var value = new UserLoginIdentifierEntity();
        value.account = account;
        value.normalizedIdentifier = identifier;
        value.identifierType = type;
        return value;
    }
}
