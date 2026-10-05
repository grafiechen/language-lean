package com.languagelean.learning;

import com.languagelean.dictionary.DictionaryService;
import com.languagelean.reviews.domain.Rating;
import com.languagelean.reviews.domain.ReviewAggregation;
import com.languagelean.sync.ReviewSubmission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

/** 复习完成提交用例：协议校验、幂等上传、基准调度和按实际答题时间更新共享进度。 */
@Service
public class ReviewService {
    private final UserLearningItemRepository items;
    private final LearningReviewEventRepository events;
    private final DictionaryService dictionary;
    private final FsrsScheduler scheduler;
    private final ObjectMapper json;
    private final long futureToleranceSeconds;
    private final PrivateEntryService privateEntries;

    /** 所有操作都在同一数据库事务中完成；不依赖客户端传入账户身份。 */
    ReviewService(UserLearningItemRepository items, LearningReviewEventRepository events,
                  DictionaryService dictionary, FsrsScheduler scheduler, ObjectMapper json,
                  @Value("${app.fsrs.future-clock-tolerance-seconds:300}") long futureToleranceSeconds, PrivateEntryService privateEntries) {
        this.items = items;
        this.events = events;
        this.dictionary = dictionary;
        this.scheduler = scheduler;
        this.json = json;
        if (futureToleranceSeconds < 0) throw new IllegalArgumentException("时钟容差不能为负数");
        this.futureToleranceSeconds = futureToleranceSeconds;
        this.privateEntries = privateEntries;
    }

    /** 自动上传和手动补传共用此入口；重复事件仅确认，不重复增加次数或更新 FSRS。 */
    @Transactional
    public SubmissionResult submit(UUID userId, ReviewSubmission request) {
        var rating = validate(request);
        var item = items.lockByIdAndUserId(request.learningItemId(), userId)
                .orElseThrow(() -> new ReviewSyncException(NOT_FOUND, "LEARNING_ITEM_MISSING", "学习条目已删除或不属于当前账户"));
        if (!item.progressEpoch.equals(request.progressEpoch()))
            throw new ReviewSyncException(CONFLICT, "PROGRESS_RESET", "学习进度已重置，旧复习记录不能恢复进度");
        var payload = json.writeValueAsString(request);
        var existing = events.findById(request.eventId());
        if (existing.isPresent()) {
            var event = existing.get();
            if (!event.userId.equals(userId) || !event.learningItemId.equals(item.id)
                    || !event.submissionJson.equals(payload))
                throw new ReviewSyncException(CONFLICT, "EVENT_CONFLICT", "同一事件标识不能用于不同复习内容");
            return result("DUPLICATE", request.eventId(), item);
        }
        if (events.findByUserIdAndAttemptId(userId, request.attemptId()).isPresent())
            throw new ReviewSyncException(CONFLICT, "ATTEMPT_CONFLICT", "此轮词条复习已经提交，请复用原事件标识");
        if (request.completedAt().isAfter(Instant.now().plusSeconds(futureToleranceSeconds)))
            throw new ReviewSyncException(BAD_REQUEST, "CLOCK_SKEW", "答题时间超过时钟容差，请检查设备时间");
        var status = item.personalCustomEntryId == null ? dictionary.learningReference(item.dictionaryEntryId).status()
            : privateEntries.reference(userId, item.personalCustomEntryId).status();
        if (!"PUBLISHED".equals(status) && !"PRIVATE".equals(status))
            throw new ReviewSyncException(CONFLICT, "WORD_UNAVAILABLE", "词条已封禁或删除，记录暂不能上传");
        var baseline = baseline(item, request);
        var scheduled = scheduler.schedule(item.id, baseline, rating, request.completedAt());
        var event = new LearningReviewEvent();
        event.id = request.eventId();
        event.userId = userId;
        event.learningItemId = item.id;
        event.attemptId = request.attemptId();
        event.progressEpoch = item.progressEpoch;
        event.baseVersion = request.baseVersion();
        event.baseEventId = request.baseEventId();
        event.completedAt = request.completedAt();
        event.receivedAt = Instant.now();
        event.finalRating = rating.name();
        event.submissionJson = payload;
        event.stateBefore = baseline;
        event.stateAfter = scheduled.state();
        event.schedulerConfiguration = scheduled.configuration();
        event.algorithmVersion = FsrsScheduler.VERSION;
        event.nextReviewAt = scheduled.due();
        events.saveAndFlush(event);

        // 历史次数统计所有唯一完成事件；当前卡片只采用实际答题时间最新的事件快照。
        item.reviewCount++;
        if (rating == Rating.AGAIN) item.lapseCount++;
        item.automaticEarFocus |= rating != Rating.GOOD;
        boolean newer = item.lastReviewedAt == null || request.completedAt().isAfter(item.lastReviewedAt)
                || request.completedAt().equals(item.lastReviewedAt)
                && (item.lastReviewEventId == null || request.eventId().toString().compareTo(item.lastReviewEventId.toString()) > 0);
        if (newer) {
            item.lastReviewedAt = request.completedAt();
            item.lastReviewEventId = request.eventId();
            item.nextReviewAt = scheduled.due();
            item.fsrsState = scheduled.state();
            item.fsrsAlgorithmVersion = FsrsScheduler.VERSION;
        }
        item.updatedAt = Instant.now();
        return result(newer ? "APPLIED" : "HISTORICAL", event.id, item);
    }

    /** 返回最近五十次完成记录；只允许读取当前账户仍存在的学习身份。 */
    @Transactional(readOnly = true)
    public List<HistoryView> history(UUID userId, UUID itemId) {
        items.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目不存在"));
        return events.findTop50ByLearningItemIdOrderByCompletedAtDescIdDesc(itemId).stream()
                .map(event -> new HistoryView(event.id, event.completedAt, event.receivedAt, event.finalRating,
                        event.baseVersion, event.baseEventId, event.algorithmVersion, event.nextReviewAt)).toList();
    }

    /** 使用客户端实际引用的快照计算，避免离线旧基准被服务器当前卡片静默替换。 */
    private String baseline(UserLearningItem item, ReviewSubmission request) {
        if ("0".equals(request.baseVersion())) {
            if (request.baseEventId() != null) throw new ResponseStatusException(BAD_REQUEST, "初始基准不能引用事件");
            return "{}";
        }
        var baseTime = parseVersion(request.baseVersion());
        if (request.results().stream().flatMap(r -> r.trials().stream()).anyMatch(t -> t.ratedAt().isBefore(baseTime)))
            throw new ResponseStatusException(BAD_REQUEST, "答题时间不能早于基准进度");
        var candidates = events.findByLearningItemIdAndCompletedAt(item.id, baseTime).stream()
                .filter(event -> request.baseEventId() == null || event.id.equals(request.baseEventId())).toList();
        if (candidates.size() != 1)
            throw new ReviewSyncException(CONFLICT, "BASELINE_MISSING", "基准进度不存在或有歧义，请先上传其基准事件");
        return candidates.getFirst().stateAfter;
    }

    /** 第一版只接受已实现的听音回忆协议；新题型应注册自己的版本校验器后再开放。 */
    private Rating validate(ReviewSubmission request) {
        if (request == null || request.eventId() == null || request.attemptId() == null
                || request.learningItemId() == null || request.progressEpoch() == null
                || request.completedAt() == null || request.baseVersion() == null
                || request.results() == null || request.results().size() != 1)
            throw new ResponseStatusException(BAD_REQUEST, "复习提交字段不完整");
        var completed = request.completedAt();
        if (!completed.equals(completed.truncatedTo(ChronoUnit.MILLIS)))
            throw new ResponseStatusException(BAD_REQUEST, "答题时间精度最多为毫秒");
        if (!completed.equals(parseVersion(request.submissionVersion())))
            throw new ResponseStatusException(BAD_REQUEST, "提交版本必须是实际完成答题时间");
        var type = request.results().getFirst();
        if (type == null || !"LISTEN_RECALL".equals(type.typeId()) || type.contractVersion() != 1
                || type.trials() == null || type.trials().isEmpty() || type.trials().size() > 1000)
            throw new ResponseStatusException(BAD_REQUEST, "题型尚未支持或尝试记录不完整");
        var ids = new HashSet<UUID>();
        Instant previous = null;
        for (int index = 0; index < type.trials().size(); index++) {
            var trial = type.trials().get(index);
            if (trial == null || trial.id() == null || trial.rating() == null || trial.ratedAt() == null
                    || !ids.add(trial.id()) || !trial.ratedAt().equals(trial.ratedAt().truncatedTo(ChronoUnit.MILLIS))
                    || previous != null && trial.ratedAt().isBefore(previous)
                    || trial.ratedAt().isAfter(completed))
                throw new ResponseStatusException(BAD_REQUEST, "尝试记录标识或时间顺序无效");
            boolean last = index == type.trials().size() - 1;
            if (last ? trial.rating() == Rating.AGAIN : trial.rating() != Rating.AGAIN)
                throw new ResponseStatusException(BAD_REQUEST, "题型必须重试到非 Again，且完成后不能继续追加尝试");
            previous = trial.ratedAt();
        }
        if (!completed.equals(previous)) throw new ResponseStatusException(BAD_REQUEST, "完成时间必须对应最后一次判定");
        return ReviewAggregation.worst(type.trials().stream().map(ReviewSubmission.Trial::rating).toList());
    }

    /** 时间戳版本统一解析，禁止数据库版本号与客户端时间戳混用。 */
    private Instant parseVersion(String value) {
        try {
            var instant = Instant.parse(value);
            if (!instant.equals(instant.truncatedTo(ChronoUnit.MILLIS))) throw new IllegalArgumentException();
            return instant;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(BAD_REQUEST, "进度版本必须是毫秒精度的 ISO 时间戳");
        }
    }

    /** 确认结果携带服务器当前状态，迟到上传也不能把本地进度回退。 */
    private SubmissionResult result(String status, UUID eventId, UserLearningItem item) {
        return new SubmissionResult(status, eventId, item.id, item.progressEpoch,
                item.lastReviewedAt == null ? "0" : item.lastReviewedAt.toString(), item.lastReviewEventId,
                item.fsrsAlgorithmVersion, item.fsrsState, item.lastReviewedAt, item.nextReviewAt,
                item.reviewCount, item.lapseCount, item.automaticEarFocus);
    }

    /** APPLIED 更新当前卡片，HISTORICAL 保存迟到历史，DUPLICATE 确认已接收事件。 */
    public record SubmissionResult(String status, UUID eventId, UUID learningItemId, UUID progressEpoch,
            String progressVersion, UUID lastReviewEventId, String fsrsAlgorithmVersion, String fsrsState,
            Instant lastReviewedAt, Instant nextReviewAt, int reviewCount, int lapseCount, boolean automaticEarFocus) {}
    /** 历史列表不直接暴露数据库实体或其他账户信息。 */
    public record HistoryView(UUID eventId, Instant completedAt, Instant receivedAt, String finalRating,
            String baseVersion, UUID baseEventId, String algorithmVersion, Instant nextReviewAt) {}
}
