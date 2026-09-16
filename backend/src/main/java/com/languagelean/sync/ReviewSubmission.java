package com.languagelean.sync;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.languagelean.reviews.domain.Rating;

/**
 * 离线复习完成事件的版本化传输契约。
 *
 * <p>事件归属必须来自认证会话。eventId 用于重复上传时幂等去重，
 * progressEpoch 用于拒绝重置前的旧事件。</p>
 */
public record ReviewSubmission(
        UUID eventId, UUID attemptId, UUID learningItemId, UUID progressEpoch,
        String baseVersion, String submissionVersion, Instant completedAt,
        List<TypeResult> results) {
    /** 一次用户判定；失败与后续重试都必须保留。 */
    public record Trial(UUID id, Instant ratedAt, Rating rating) {}
    /** 一个题型在本轮中的协议版本和全部判定。 */
    public record TypeResult(String typeId, int contractVersion, List<Trial> trials) {}
}
