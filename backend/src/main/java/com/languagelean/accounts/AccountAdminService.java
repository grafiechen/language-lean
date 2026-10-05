package com.languagelean.accounts;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 管理员账号用例，维护登录标识唯一性、邮件发送和最后管理员保护。 */
@Service
class AccountAdminService {
    private final UserAccountRepository accounts;
    private final UserLoginIdentifierRepository identifiers;
    private final PasswordEncoder encoder;
    private final AccountMailPort mail;
    private final AccountAdminAuditRepository audit;
    AccountAdminService(UserAccountRepository accounts, UserLoginIdentifierRepository identifiers,
                        PasswordEncoder encoder, AccountMailPort mail, AccountAdminAuditRepository audit) {
        this.accounts = accounts; this.identifiers = identifiers; this.encoder = encoder; this.mail = mail; this.audit = audit;
    }
    /** 每页 20 项；使用 DTO，不序列化包含密码哈希的实体。 */
    @Transactional(readOnly = true)
    Results list(String query, AccountStatus status, int page) {
        if (page < 0 || page > 100000 || query.length() > 320) throw new ResponseStatusException(BAD_REQUEST, "搜索条件或页码不合法");
        String pattern = "%" + UserAccountEntity.normalize(query).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var result = accounts.search(pattern, status, PageRequest.of(page, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new Results(result.getContent().stream().map(View::from).toList(), result.getTotalElements(), page);
    }
    /** 先验证唯一性并 flush，再投递初始密码；投递失败整笔事务回滚。 */
    @Transactional
    View create(UUID actor, String username, String email, boolean administrator) {
        accounts.lockRoleMembers(Role.ADMIN); requireActor(actor);
        username = username == null ? "" : username.trim(); email = email == null ? "" : email.trim();
        if (username.isBlank() || username.length() > 80 || username.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(BAD_REQUEST, "用户名必填，最多 80 个字符且不能包含控制字符");
        if (!AccountMailConfiguration.validEmail(email)) throw new ResponseStatusException(BAD_REQUEST, "请填写有效的单个邮箱");
        var nameKey = UserAccountEntity.normalize(username); var emailKey = UserAccountEntity.normalize(email);
        if (nameKey.equals(emailKey)) throw new ResponseStatusException(BAD_REQUEST, "用户名和邮箱不能相同");
        if (identifiers.existsById(nameKey) || identifiers.existsById(emailKey))
            throw new ResponseStatusException(CONFLICT, "用户名或邮箱已存在，请使用其他登录标识");
        if (!mail.configured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "账号邮件未配置，暂时不能创建账号");
        String password = AccountSecrets.initialPassword();
        var account = accounts.saveAndFlush(UserAccountEntity.create(username, email, encoder.encode(password),
                administrator ? Set.of(Role.USER, Role.ADMIN) : Set.of(Role.USER)));
        mail.sendInitialPassword(email, username, password);
        audit.save(new AccountAdminAudit(actor, account.getId(), "CREATE"));
        return View.from(account);
    }
    /** 所有管理员状态变更按固定锁序串行，且等待锁后再次检查操作人的状态。 */
    @Transactional
    View changeStatus(UUID actor, UUID id, long version, AccountStatus status) {
        var administrators = accounts.lockRoleMembers(Role.ADMIN);
        requireActor(actor);
        var account = accounts.lockById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "账号不存在"));
        if (version != account.getVersion()) throw new ResponseStatusException(CONFLICT, "账号已被修改，请刷新后重试");
        if (status == null) throw new ResponseStatusException(BAD_REQUEST, "请选择账号状态");
        if (account.getStatus() == status) return View.from(account);
        if (status == AccountStatus.DISABLED && actor.equals(id)) throw new ResponseStatusException(BAD_REQUEST, "不能禁用自己的账号");
        if (status == AccountStatus.DISABLED && account.getRoles().contains(Role.ADMIN)
                && administrators.stream().filter(a -> a.getStatus() == AccountStatus.ACTIVE).count() <= 1)
            throw new ResponseStatusException(BAD_REQUEST, "必须保留至少一个启用的管理员");
        account.changeStatus(status); accounts.flush();
        audit.save(new AccountAdminAudit(actor, id, status == AccountStatus.ACTIVE ? "ENABLE" : "DISABLE"));
        return View.from(account);
    }
    private void requireActor(UUID id) {
        var actor = accounts.findById(id).orElseThrow(() -> new ResponseStatusException(FORBIDDEN, "管理员账号不可用"));
        if (actor.getStatus() != AccountStatus.ACTIVE || !actor.getRoles().contains(Role.ADMIN))
            throw new ResponseStatusException(FORBIDDEN, "管理员账号不可用");
    }
    record Results(List<View> items, long total, int page) {}
    record View(UUID id, String username, String email, AccountStatus status, Set<Role> roles,
                boolean mustChangePassword, long version, Instant createdAt) {
        static View from(UserAccountEntity a) { return new View(a.getId(), a.getUsername(), a.getEmail(), a.getStatus(), a.getRoles(), a.isMustChangePassword(), a.getVersion(), a.getCreatedAt()); }
    }
}
