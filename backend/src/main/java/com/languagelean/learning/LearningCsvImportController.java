package com.languagelean.learning;

import com.languagelean.accounts.UserAccountPrincipal;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 受认证和CSRF保护的本人CSV导入；不接受客户端指定账户身份。 */
@RestController
@RequestMapping("/api/v1/learning/wordbooks/{bookId}/csv")
class LearningCsvImportController {
    private final LearningCsvImportService imports;
    LearningCsvImportController(LearningCsvImportService imports) { this.imports = imports; }
    /** 文件预检只返回报告，不生成任何个人数据。 */
    @PostMapping("/preview")
    LearningCsvImportService.Report preview(@PathVariable UUID bookId, @RequestParam MultipartFile file,
            @RequestParam String translationLanguage, @AuthenticationPrincipal UserAccountPrincipal user) throws IOException {
        return imports.preview(user.userId(), bookId, file.getBytes(), translationLanguage);
    }
    /** 再次上传同一文件和预检哈希，避免确认时文件已经被替换。 */
    @PostMapping("/confirm")
    LearningCsvImportService.Report confirm(@PathVariable UUID bookId, @RequestParam MultipartFile file,
            @RequestParam String translationLanguage, @RequestParam String fileHash,
            @AuthenticationPrincipal UserAccountPrincipal user) throws IOException {
        return imports.confirm(user.userId(), bookId, file.getBytes(), translationLanguage, fileHash);
    }
}
