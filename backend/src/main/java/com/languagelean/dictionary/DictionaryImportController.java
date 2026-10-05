package com.languagelean.dictionary;

import java.util.List;
import java.util.UUID;
import com.languagelean.accounts.UserAccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 管理员词典导入端点；上传预览和确认发布是两个显式动作。 */
@RestController
@RequestMapping("/api/v1/admin/dictionary/imports")
class DictionaryImportController {
    private final DictionaryImportService imports;
    DictionaryImportController(DictionaryImportService imports) { this.imports = imports; }

    /** 上传 UTF-8 JSON 并创建可持久恢复的校验预览。 */
    @PostMapping(path = "/preview", consumes = "multipart/form-data")
    DictionaryImportService.BatchView preview(@RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return imports.preview(file, user.userId());
    }
    /** 最近批次用于刷新页面后继续审核。 */
    @GetMapping
    List<DictionaryImportService.BatchSummary> list() { return imports.list(); }
    /** 查看逐条校验结果和文件哈希。 */
    @GetMapping("/{id}")
    DictionaryImportService.BatchView detail(@PathVariable UUID id) { return imports.detail(id); }
    /** 确认后批量发布所有 READY 项，重复项仍跳过。 */
    @PostMapping("/{id}/apply")
    DictionaryImportService.BatchView apply(@PathVariable UUID id,
            @RequestBody DictionaryImportService.Apply request,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return imports.apply(id, request, user.userId());
    }
}
