package com.languagelean.dictionary;

import java.util.UUID;
import com.languagelean.accounts.UserAccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 管理员基准词典端点；身份取自会话，客户端不能指定发布人。 */
@RestController
@RequestMapping("/api/v1/admin/dictionary")
class DictionaryAdminController {
    private final DictionaryService dictionary;
    DictionaryAdminController(DictionaryService dictionary) { this.dictionary = dictionary; }
    /** 分页搜索后台所有词条。 */
    @GetMapping
    DictionaryService.Results<DictionaryService.Row> list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String language, @RequestParam(defaultValue = "0") int page) {
        return dictionary.list(true, q, language, page);
    }
    /** 新建尚未公开的草稿。 */
    @PostMapping
    DictionaryService.AdminView create(@RequestBody DictionaryService.Create request, @AuthenticationPrincipal UserAccountPrincipal user) {
        return dictionary.create(request, user.userId());
    }
    /** 读取草稿和公开版。 */
    @GetMapping("/{id}")
    DictionaryService.AdminView detail(@PathVariable UUID id) { return dictionary.adminDetail(id); }
    /** 保存草稿不改变公开内容。 */
    @PostMapping("/{id}/draft")
    DictionaryService.AdminView save(@PathVariable UUID id, @RequestBody DictionaryService.Edit request, @AuthenticationPrincipal UserAccountPrincipal user) {
        return dictionary.save(id, request, user.userId());
    }
    /** 管理员确认并发布完整快照。 */
    @PostMapping("/{id}/publish")
    DictionaryService.AdminView publish(@PathVariable UUID id, @RequestBody DictionaryService.Action request, @AuthenticationPrincipal UserAccountPrincipal user) {
        return dictionary.publish(id, request, user.userId());
    }
    /** 保留身份并封禁公开内容。 */
    @PostMapping("/{id}/ban")
    DictionaryService.AdminView ban(@PathVariable UUID id, @RequestBody DictionaryService.Action request, @AuthenticationPrincipal UserAccountPrincipal user) {
        return dictionary.ban(id, request, user.userId());
    }
    /** 查看线性的发布历史。 */
    @GetMapping("/{id}/history")
    DictionaryService.Results<DictionaryService.History> history(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
        return dictionary.history(id, page);
    }
    /** 从历史复制草稿，不直接回退用户可见版本。 */
    @PostMapping("/{id}/history/{revision}/restore")
    DictionaryService.AdminView restore(@PathVariable UUID id, @PathVariable int revision,
            @RequestBody DictionaryService.Action request, @AuthenticationPrincipal UserAccountPrincipal user) {
        return dictionary.restore(id, revision, request, user.userId());
    }
}
