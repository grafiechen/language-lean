package com.languagelean.learning;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.languagelean.sync.ReviewSubmission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;

/** 个人数据备份边界：使用JPA读取一致快照，绝不导出密码、会话、邮箱或他人内容。 */
@Service
public class LearningExportService {
    private final com.languagelean.audio.AudioBackupService audio;
    private final LearningService learning;
    private final UserLearningItemRepository items;
    private final PrivateEntryRepository privateEntries;
    private final PrivateEntryService privateService;
    private final PersonalContentService personal;
    private final LearningReviewEventRepository events;
    private final WordbookLearningItemRepository links;
    private final ObjectMapper json;
    LearningExportService(LearningService learning, UserLearningItemRepository items, PrivateEntryRepository privateEntries,
        PrivateEntryService privateService, PersonalContentService personal, LearningReviewEventRepository events,
        WordbookLearningItemRepository links, ObjectMapper json, com.languagelean.audio.AudioBackupService audio) {
        this.learning = learning; this.items = items; this.privateEntries = privateEntries; this.privateService = privateService;
        this.personal = personal; this.events = events; this.links = links; this.json = json; this.audio = audio;
    }

    /** 不分页截断个人学习历史；数据库快照与客户端本机未上传记录分开标识。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export export(UUID userId) {
        var books = learning.listWordbooks(userId).stream().map(book -> new LearningService.BookState(book,
            links.findByWordbook(book.id()).stream().map(link -> link.id.learningItemId).toList())).toList();
        var contents = items.findByUserIdOrderByIdAsc(userId).stream().map(item -> new ExportItem(
            item.id, item.dictionaryEntryId, item.personalCustomEntryId, item.manualEarFocus, item.automaticEarFocus,
            item.progressEpoch, item.fsrsAlgorithmVersion, item.fsrsState, item.lastReviewedAt, item.nextReviewAt,
            item.reviewCount, item.lapseCount, item.lastReviewEventId, personal.exportOwned(userId, item.id))).toList();
        var privateContent = privateEntries.findByUserIdOrderByIdAsc(userId).stream().map(entry -> privateService.detail(userId, entry.id)).toList();
        var history = events.findByUserIdOrderByCompletedAtAscIdAsc(userId).stream().map(event -> new ExportReview(
            event.id, event.learningItemId, event.progressEpoch, event.completedAt, event.receivedAt, event.finalRating,
            json.readValue(event.submissionJson, ReviewSubmission.class), event.stateBefore, event.stateAfter,
            event.schedulerConfiguration, event.algorithmVersion, event.nextReviewAt)).toList();
        return new Export(1, "SERVER", userId, Instant.now(), books, contents, privateContent, history, audio.metadata(userId));
    }
    /** 备份版本用于后续恢复适配；当前导出不会触发上传、恢复或重新计算进度。 */
    public record Export(int schemaVersion, String scope, UUID userId, Instant exportedAt, List<LearningService.BookState> wordbooks,
        List<ExportItem> items, List<PrivateEntryService.View> privateEntries, List<ExportReview> reviews,
        List<com.languagelean.audio.AudioBackupService.Resource> audioResources) {}
    public record ExportItem(UUID id, UUID dictionaryEntryId, UUID personalCustomEntryId, boolean manualEarFocus, boolean automaticEarFocus,
        UUID progressEpoch, String algorithmVersion, String fsrsState, Instant lastReviewedAt, Instant nextReviewAt,
        int reviewCount, int lapseCount, UUID lastReviewEventId, PersonalContentService.Personal personal) {}
    public record ExportReview(UUID eventId, UUID learningItemId, UUID progressEpoch, Instant completedAt, Instant receivedAt,
        String finalRating, ReviewSubmission submission, String stateBefore, String stateAfter, String schedulerConfiguration,
        String algorithmVersion, Instant nextReviewAt) {}
}
