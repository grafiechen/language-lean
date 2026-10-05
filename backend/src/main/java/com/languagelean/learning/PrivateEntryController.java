package com.languagelean.learning;
import com.languagelean.accounts.UserAccountPrincipal;
import com.languagelean.dictionary.DictionaryService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
/** 私有词条管理；所有身份来自会话，创建和编辑不经过公开发布接口。 */
@RestController @RequestMapping("/api/v1/learning/private-entries")
class PrivateEntryController {
    private final PrivateEntryService entries;
    private final LearningService learning;
    PrivateEntryController(PrivateEntryService entries, LearningService learning) { this.entries = entries; this.learning = learning; }
    /** 查询本人的私有词条。 */
    @GetMapping DictionaryService.Results<PrivateEntryService.View> list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page, @AuthenticationPrincipal UserAccountPrincipal user) { return entries.list(user.userId(), q, page); }
    /** 读详情不允许管理员访问其他用户私有内容。 */
    @GetMapping("/{id}") PrivateEntryService.View detail(@PathVariable UUID id, @AuthenticationPrincipal UserAccountPrincipal user) { return entries.detail(user.userId(), id); }
    /** 可先只保存写法再补充内容。 */
    @PostMapping PrivateEntryService.View create(@RequestBody PrivateEntryService.Create request,
            @RequestHeader("X-Learning-Account") UUID expectedUserId, @AuthenticationPrincipal UserAccountPrincipal user) {
        if (!user.userId().equals(expectedUserId)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "登录账号已变化，请重新加载后录入");
        return entries.create(user.userId(), request);
    }
    /** 保存要求读到的版本。 */
    @PutMapping("/{id}") PrivateEntryService.View save(@PathVariable UUID id, @RequestBody PrivateEntryService.Edit request, @AuthenticationPrincipal UserAccountPrincipal user) { return entries.save(user.userId(), id, request); }
    /** 显式彻底删除本人内容及学习数据。 */
    @DeleteMapping("/{id}") LearningService.DeletionResult delete(@PathVariable UUID id, @AuthenticationPrincipal UserAccountPrincipal user) { return learning.deletePrivateEntry(user.userId(), id); }
}
