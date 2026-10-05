package com.languagelean.learning;

import java.util.*;
import com.languagelean.sync.ReviewSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** 本机备份恢复的只读核对；不重建已删除身份，不改写服务器进度或私人内容。 */
@Service
class LearningRecoveryService {
    private final LearningService learning;
    private final LearningReviewEventRepository reviews;
    private final ObjectMapper json;
    LearningRecoveryService(LearningService learning, LearningReviewEventRepository reviews, ObjectMapper json) {
        this.learning = learning; this.reviews = reviews; this.json = json;
    }
    /** 同一数据库快照核对归属、重置代次及已接收事件；外账户事件不暴露正文。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Check check(UUID owner, Request request) {
        if (request == null || request.eventIds() == null || request.eventIds().size() > 200
                || request.eventIds().stream().anyMatch(Objects::isNull))
            throw new ResponseStatusException(BAD_REQUEST, "每次最多检查 200 个事件，ID 不能为空");
        var state = learning.reconcile(owner, new LearningService.ReconcileRequest(request.learningItemIds(), request.wordbookIds()));
        var requested = new LinkedHashSet<>(request.eventIds());
        var found = new HashMap<UUID, LearningReviewEvent>();
        reviews.findAllById(requested).forEach(event -> found.put(event.id, event));
        var events = requested.stream().map(id -> {
            var event = found.get(id);
            if (event == null) return new Event(id, "MISSING", null);
            if (!event.userId.equals(owner)) return new Event(id, "CONFLICT", null);
            return new Event(id, "ACCEPTED", json.readValue(event.submissionJson, ReviewSubmission.class));
        }).toList();
        return new Check(state, events);
    }
    /** 账号另在控制器绑定当前会话，防止标签页切换时误读另一账号的恢复结论。 */
    record Request(UUID accountId, List<UUID> learningItemIds, List<UUID> wordbookIds, List<UUID> eventIds) {}
    /** 分组结论完整返回，客户端不能把缺失响应当成删除或接收确认。 */
    record Check(LearningService.ReconcileView state, List<Event> events) {}
    /** 已接收仅返回本人的规范提交，供客户端比较正文并避免重复恢复/计数。 */
    record Event(UUID eventId, String status, ReviewSubmission submission) {}
}
