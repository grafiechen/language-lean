package com.languagelean.learning;

import com.languagelean.accounts.UserAccountPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前账户的单词本和学习条目 API；账户范围始终来自认证会话。 */
@RestController
@RequestMapping("/api/v1/learning")
class LearningController {
    private final LearningService learning;
    private final LearningExportService exports;
    private final LearningRecoveryService recovery;

    LearningController(LearningService learning, LearningExportService exports, LearningRecoveryService recovery) {
        this.learning = learning; this.exports = exports; this.recovery = recovery;
    }
    /** 恢复预检不写入服务器；固定学习账号，避免切换会话后错误接受备份。 */
    @PostMapping("/recovery-check")
    LearningRecoveryService.Check recoveryCheck(@RequestBody LearningRecoveryService.Request request,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        if (request == null || !user.userId().equals(request.accountId()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "恢复账号与当前登录账号不一致");
        return recovery.check(user.userId(), request);
    }
    /** 导出身份只取当前会话，不接受客户端请求其他账号备份。 */
    @GetMapping("/export")
    LearningExportService.Export export(@AuthenticationPrincipal UserAccountPrincipal user) { return exports.export(user.userId()); }
    /** 手动重点只改当前账号的共享学习条目，不影响自动重点及FSRS。 */
    @PostMapping("/items/{itemId}/ear-focus")
    LearningService.LearningItemView earFocus(@PathVariable UUID itemId, @RequestBody EarFocus request,
        @AuthenticationPrincipal UserAccountPrincipal user) { return learning.setManualEarFocus(user.userId(), itemId, request.enabled()); }
    record EarFocus(Boolean enabled) {}

    /** 列出当前账户的单词本和词条数量。 */
    @GetMapping("/wordbooks")
    List<LearningService.WordbookView> wordbooks(@AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.listWordbooks(user.userId());
    }

    /** 只读但携带本机 ID 列表，采用受 CSRF 保护的 POST，防止身份出现在 URL。 */
    @PostMapping("/reconcile")
    LearningService.ReconcileView reconcile(@RequestBody LearningService.ReconcileRequest request,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.reconcile(user.userId(), request);
    }

    /** 创建一个空单词本。 */
    @PostMapping("/wordbooks")
    LearningService.WordbookView createWordbook(@RequestBody LearningService.CreateWordbook request,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.createWordbook(user.userId(), request);
    }

    /** 查看指定单词本中的共享学习条目。 */
    @GetMapping("/wordbooks/{wordbookId}/items")
    List<LearningService.LearningItemView> items(@PathVariable UUID wordbookId,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.listItems(user.userId(), wordbookId);
    }

    /** 返回当前单词本的到期复习条目；到期词优先于尚未初始化的词。 */
    @GetMapping("/wordbooks/{wordbookId}/review-queue")
    List<LearningService.LearningItemView> reviewQueue(@PathVariable UUID wordbookId,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.reviewQueue(user.userId(), wordbookId);
    }

    /** 将已发布公开词条加入单词本，重复调用保持幂等。 */
    @PostMapping("/wordbooks/{wordbookId}/entries/{dictionaryEntryId}")
    LearningService.LearningItemView addEntry(@PathVariable UUID wordbookId, @PathVariable UUID dictionaryEntryId,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.addDictionaryEntry(user.userId(), wordbookId, dictionaryEntryId);
    }

    /** 私有词条与公开词条共享单词本分类和进度规则。 */
    @PostMapping("/wordbooks/{wordbookId}/private-entries/{entryId}")
    LearningService.LearningItemView addPrivate(@PathVariable UUID wordbookId, @PathVariable UUID entryId,
            @AuthenticationPrincipal UserAccountPrincipal user) { return learning.addPrivateEntry(user.userId(), wordbookId, entryId); }
    /** 客户端不必按词条来源拼出不同删除路径。 */
    @DeleteMapping("/wordbooks/{wordbookId}/items/{itemId}")
    LearningService.DeletionResult removeItem(@PathVariable UUID wordbookId, @PathVariable UUID itemId,
            @AuthenticationPrincipal UserAccountPrincipal user) { return learning.removeLearningItem(user.userId(), wordbookId, itemId); }
    /** 从单词本移除词条；最后一个关联移除时一并清空共享学习条目。 */
    @DeleteMapping("/wordbooks/{wordbookId}/entries/{dictionaryEntryId}")
    LearningService.DeletionResult removeEntry(@PathVariable UUID wordbookId, @PathVariable UUID dictionaryEntryId,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.removeDictionaryEntry(user.userId(), wordbookId, dictionaryEntryId);
    }

    /** 删除单词本，并按最后关联规则清除孤立学习条目。 */
    @DeleteMapping("/wordbooks/{wordbookId}")
    LearningService.DeletionResult deleteWordbook(@PathVariable UUID wordbookId, @AuthenticationPrincipal UserAccountPrincipal user) {
        return learning.deleteWordbook(user.userId(), wordbookId);
    }

    /** 完整重置单词本内所有共享进度和手动重点。 */
    @PostMapping("/wordbooks/{wordbookId}/reset")
    void resetWordbook(@PathVariable UUID wordbookId, @AuthenticationPrincipal UserAccountPrincipal user) {
        learning.resetWordbook(user.userId(), wordbookId);
    }
}
