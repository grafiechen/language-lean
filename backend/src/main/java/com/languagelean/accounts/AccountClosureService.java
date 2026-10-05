package com.languagelean.accounts;

import java.util.UUID;
import com.languagelean.audio.AudioCleanupService;
import com.languagelean.learning.AccountLearningCleanup;
import com.languagelean.dictionary.AccountContributionCleanup;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 账号注销的单事务编排：重新认证、最后管理员保护、私人数据和云对象清理。 */
@Service
class AccountClosureService {
    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final AccountAdminAuditRepository audit;
    private final AccountLearningCleanup learning;
    private final AccountContributionCleanup contributions;
    private final AudioCleanupService audio;
    AccountClosureService(UserAccountRepository accounts, PasswordEncoder encoder, AccountAdminAuditRepository audit,
                          AccountLearningCleanup learning, AccountContributionCleanup contributions, AudioCleanupService audio) {
        this.accounts = accounts; this.encoder = encoder; this.audit = audit;
        this.learning = learning; this.contributions = contributions; this.audio = audio;
    }
    /** 本人注销必须验证当前密码和完整用户名，不接受客户端指定其他账号。 */
    @Transactional
    void closeSelf(UUID actor, String password, String confirmation) { close(actor, actor, null, password, confirmation, false); }
    /** 后台删除验证管理员自己的密码，目标版本与确认用户名防止错删。 */
    @Transactional
    void closeByAdmin(UUID actor, UUID target, Long version, String password, String confirmation) {
        if (version == null) throw new ResponseStatusException(BAD_REQUEST, "请先读取账号版本");
        close(actor, target, version, password, confirmation, true);
    }
    private void close(UUID actorId, UUID targetId, Long version, String password, String confirmation, boolean adminOperation) {
        var administrators = accounts.lockRoleMembers(Role.ADMIN);
        var actor = accounts.lockById(actorId).orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "账号已不存在"));
        if (actor.getStatus() != AccountStatus.ACTIVE || adminOperation && !actor.getRoles().contains(Role.ADMIN))
            throw new ResponseStatusException(FORBIDDEN, "当前账号不能执行此操作");
        if (password == null || !encoder.matches(password, actor.getPasswordHash()))
            throw new ResponseStatusException(BAD_REQUEST, "当前密码不正确");
        var found = accounts.lockById(targetId);
        if (found.isEmpty() && adminOperation) return; // 网络重试按旧 UUID 幂等，不会操作新建的同名账号。
        var target = found.orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "账号不存在"));
        if (version != null && version != target.getVersion()) throw new ResponseStatusException(CONFLICT, "账号已被修改，请刷新后重试");
        if (!target.getUsername().equals(confirmation)) throw new ResponseStatusException(BAD_REQUEST, "请完整输入被删除账号的用户名");
        if (adminOperation && actorId.equals(targetId)) throw new ResponseStatusException(BAD_REQUEST, "请在个人首页使用注销账号");
        if (target.getRoles().contains(Role.ADMIN) && target.getStatus() == AccountStatus.ACTIVE
                && administrators.stream().filter(a -> a.getStatus() == AccountStatus.ACTIVE).count() <= 1)
            throw new ResponseStatusException(BAD_REQUEST, "必须保留至少一个启用的管理员");
        // 源数据先锁，后查全部资产，防止已开始的 TTS 在对象键清理后插入新版本。
        learning.lock(targetId); contributions.anonymize(targetId); audio.scheduleAccount(targetId);
        audit.save(new AccountAdminAudit(actorId, targetId, "DELETE"));
        // ORM 删除账号；外键级联彻底删除分类、进度、历史、覆盖、私人词条、投稿及重置凭据。
        accounts.delete(target); accounts.flush();
    }
}
