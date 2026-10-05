package com.languagelean.accounts;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在线请求回查账号凭据；只比较密码，母语或其他偏好变化不注销会话。 */
@Service
class AccountSessionService {
    private final UserAccountRepository accounts;
    AccountSessionService(UserAccountRepository accounts) { this.accounts = accounts; }
    @Transactional(readOnly = true)
    UserAccountPrincipal principal(UUID id) { return accounts.findById(id).map(UserAccountPrincipal::from).orElse(null); }
}
