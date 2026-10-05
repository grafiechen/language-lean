package com.languagelean.learning;

import com.languagelean.accounts.UserAccountPrincipal;
import com.languagelean.sync.ReviewSubmission;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 登录用户的复习提交和历史 API；账户归属由认证会话提供。 */
@RestController
@RequestMapping("/api/v1/learning")
class ReviewController {
    private final ReviewService reviews;
    /** 注入同一复习用例，自动与手动上传不使用两套进度逻辑。 */
    ReviewController(ReviewService reviews) { this.reviews = reviews; }

    /** 每个词条完成全部必做题型后立即调用；网络重试必须保留原事件标识。 */
    @PostMapping("/reviews")
    ReviewService.SubmissionResult submit(@RequestBody ReviewSubmission request,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return reviews.submit(user.userId(), request);
    }

    /** 查询当前账户一个学习条目的最近完成历史。 */
    @GetMapping("/items/{itemId}/reviews")
    List<ReviewService.HistoryView> history(@PathVariable UUID itemId,
            @AuthenticationPrincipal UserAccountPrincipal user) {
        return reviews.history(user.userId(), itemId);
    }

    /** 业务冲突不返回数据库异常或其他账户信息，只有可展示的稳定码和说明。 */
    @ExceptionHandler(ReviewSyncException.class)
    org.springframework.http.ResponseEntity<SyncError> syncError(ReviewSyncException failure) {
        return org.springframework.http.ResponseEntity.status(failure.getStatusCode())
                .body(new SyncError(failure.code(), failure.getReason()));
    }
    record SyncError(String code, String detail) {}
}
