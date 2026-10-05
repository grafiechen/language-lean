package com.languagelean.learning;

import com.languagelean.dictionary.DictionaryService;
import com.languagelean.dictionary.ContributionRepository;
import com.languagelean.audio.AudioCleanupService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/**
 * 个人学习条目和单词本用例边界。
 *
 * <p>单词本只保存分类关联，FSRS 字段始终属于用户学习条目；因此同一个词加入多个单词本
 * 时会复用一份进度。复习提交和重置通过同一学习条目行锁串行化。</p>
 */
@Service
public class LearningService {
    private final WordbookRepository wordbooks;
    private final UserLearningItemRepository learningItems;
    private final WordbookLearningItemRepository links;
    private final DictionaryService dictionary;
    private final LearningReviewEventRepository reviews;
    private final FsrsScheduler scheduler;
    private final PersonalContentService personal;
    private final PrivateEntryService privateEntries;
    private final AudioCleanupService cleanup;
    private final ContributionRepository contributions;

    LearningService(WordbookRepository wordbooks, UserLearningItemRepository learningItems,
                    WordbookLearningItemRepository links, DictionaryService dictionary,
                    LearningReviewEventRepository reviews, FsrsScheduler scheduler, PersonalContentService personal, PrivateEntryService privateEntries, AudioCleanupService cleanup, ContributionRepository contributions) {
        this.wordbooks = wordbooks;
        this.learningItems = learningItems;
        this.links = links;
        this.dictionary = dictionary;
        this.reviews = reviews;
        this.scheduler = scheduler;
        this.personal = personal;
        this.privateEntries = privateEntries;
        this.cleanup = cleanup;
        this.contributions = contributions;
    }

    /** 返回当前账户自己的单词本；数量来自关联表而不是复制到单词本。 */
    @Transactional(readOnly = true)
    public List<WordbookView> listWordbooks(UUID userId) {
        return wordbooks.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream().map(this::wordbookView).toList();
    }

    /** 创建单词本，同一账户内名称不允许重复。 */
    @Transactional
    public WordbookView createWordbook(UUID userId, CreateWordbook request) {
        var name = required(request == null ? null : request.name(), 100, "单词本名称");
        var description = text(request == null ? null : request.description(), 500, "单词本说明");
        if (wordbooks.existsByUserIdAndName(userId, name))
            throw new ResponseStatusException(CONFLICT, "该单词本名称已存在");
        var wordbook = wordbooks.saveAndFlush(Wordbook.create(userId, name, description));
        return wordbookView(wordbook);
    }

    /** 查看某个单词本中的学习条目，已封禁词条仍返回身份和状态提示。 */
    @Transactional(readOnly = true)
    public List<LearningItemView> listItems(UUID userId, UUID wordbookId) {
        requireWordbook(userId, wordbookId);
        return links.findByWordbook(wordbookId).stream()
                .map(link -> learningItems.findById(link.id.learningItemId))
                .flatMap(java.util.Optional::stream)
                .map(this::learningItemView)
                .toList();
    }

    /**
     * 返回当前单词本的首版复习队列。
     *
     * <p>筛选公开且已到期的学习条目，并将已经有下次复习时间的到期词排在新词前面；
     * 听音训练接入时还需要根据发音与音频准备状态筛选。被封禁的词条不会进入训练。</p>
     */
    @Transactional(readOnly = true)
    public List<LearningItemView> reviewQueue(UUID userId, UUID wordbookId) {
        return listItems(userId, wordbookId).stream()
                .filter(item -> ("PUBLISHED".equals(item.status()) || "PRIVATE".equals(item.status())) && item.due())
                .sorted(Comparator.comparing((LearningItemView item) -> item.nextReviewAt() == null ? 1 : 0)
                        .thenComparing(item -> item.nextReviewAt() == null ? Instant.MAX : item.nextReviewAt())
                        .thenComparing(LearningItemView::id))
                .toList();
    }

    /** 一致快照确认本机身份是否仍存在；未知或外账户 ID 不泄露其真实状态。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReconcileView reconcile(UUID userId, ReconcileRequest request) {
        if (request == null || request.learningItemIds() == null || request.wordbookIds() == null
                || request.learningItemIds().size() > 200 || request.wordbookIds().size() > 200
                || request.learningItemIds().stream().anyMatch(java.util.Objects::isNull)
                || request.wordbookIds().stream().anyMatch(java.util.Objects::isNull))
            throw new ResponseStatusException(BAD_REQUEST, "每次最多检查 200 个学习身份和 200 个单词本，ID 不能为空");
        var requested = new java.util.LinkedHashSet<>(request.learningItemIds());
        var present = requested.isEmpty() ? List.<LearningItemView>of()
                : learningItems.findByUserIdAndIdIn(userId, requested).stream().map(this::learningItemView).toList();
        var found = present.stream().map(LearningItemView::id).collect(java.util.stream.Collectors.toSet());
        var books = new java.util.ArrayList<BookState>();
        var missingBooks = new java.util.ArrayList<UUID>();
        for (var id : new java.util.LinkedHashSet<>(request.wordbookIds())) {
            var book = wordbooks.findByIdAndUserId(id, userId);
            if (book.isEmpty()) missingBooks.add(id);
            else books.add(new BookState(wordbookView(book.get()),
                    links.findByWordbook(id).stream().map(link -> link.id.learningItemId).toList()));
        }
        return new ReconcileView(userId, present, requested.stream().filter(id -> !found.contains(id)).toList(), books, missingBooks);
    }

    /** 将公开词条加入单词本；同一用户的多个单词本共享已有学习条目。 */
    @Transactional
    public LearningItemView addDictionaryEntry(UUID userId, UUID wordbookId, UUID dictionaryEntryId) {
        requireWordbook(userId, wordbookId);
        dictionary.requireLearningReference(dictionaryEntryId);
        var item = learningItems.findByUserIdAndDictionaryEntryId(userId, dictionaryEntryId)
                .orElseGet(() -> learningItems.saveAndFlush(UserLearningItem.forDictionary(userId, dictionaryEntryId)));
        if (!links.existsByIdWordbookIdAndIdLearningItemId(wordbookId, item.id))
            links.saveAndFlush(WordbookLearningItem.create(wordbookId, item.id));
        return learningItemView(item);
    }

    /** 同一私有词条加入多个单词本复用一份进度，归属不能由管理员绕过。 */
    @Transactional
    public LearningItemView addPrivateEntry(UUID userId, UUID bookId, UUID entryId) {
        requireWordbook(userId, bookId); privateEntries.detail(userId, entryId);
        var item = learningItems.findByUserIdAndPersonalCustomEntryId(userId, entryId)
            .orElseGet(() -> learningItems.saveAndFlush(UserLearningItem.forPrivate(userId, entryId)));
        if (!links.existsByIdWordbookIdAndIdLearningItemId(bookId, item.id)) links.saveAndFlush(WordbookLearningItem.create(bookId, item.id));
        return learningItemView(item);
    }
    /** 显式删除私有词条及其所有分类、进度和内容，返回失效身份供本地清理。 */
    @Transactional
    public DeletionResult deletePrivateEntry(UUID userId, UUID entryId) {
        privateEntries.detail(userId, entryId);
        var item = learningItems.findByUserIdAndPersonalCustomEntryId(userId, entryId);
        item.ifPresent(value -> learningItems.lockByIdAndUserId(value.id, userId));
        privateEntries.delete(userId, entryId);
        return new DeletionResult(item.map(value -> List.of(value.id)).orElse(List.of()));
    }
    /** 从单词本移除关联；只有最后一个单词本移除后才删除学习条目和进度。 */
    @Transactional
    public DeletionResult removeDictionaryEntry(UUID userId, UUID wordbookId, UUID dictionaryEntryId) {
        requireWordbook(userId, wordbookId);
        var item = learningItems.findByUserIdAndDictionaryEntryId(userId, dictionaryEntryId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "该词条尚未加入学习"));
        return removeLearningItem(userId, wordbookId, item.id);
    }
    /** 公私词条均按学习身份移除，最后关联时私有内容和音频也彻底清理。 */
    @Transactional
    public DeletionResult removeLearningItem(UUID userId, UUID wordbookId, UUID itemId) {
        requireWordbook(userId, wordbookId);
        var item = learningItems.lockByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目已删除"));
        var linkId = new WordbookLearningItemId(wordbookId, item.id);
        if (!links.existsById(linkId)) throw new ResponseStatusException(NOT_FOUND, "该词条不在此单词本");
        links.deleteById(linkId);
        if (links.countByIdLearningItemId(item.id) == 0) {
            contributions.deleteUnpublishedLearning(userId, item.id);
            cleanup.scheduleLearningItem(item.id);
            if (item.personalCustomEntryId != null) privateEntries.delete(userId, item.personalCustomEntryId);
            else learningItems.delete(item);
            return new DeletionResult(List.of(item.id));
        }
        return new DeletionResult(List.of());
    }

    /** 删除单词本并按最后关联规则清理孤立学习条目。 */
    @Transactional
    public DeletionResult deleteWordbook(UUID userId, UUID wordbookId) {
        requireWordbook(userId, wordbookId);
        var itemIds = links.findByWordbook(wordbookId).stream().map(link -> link.id.learningItemId).sorted().toList();
        itemIds.forEach(itemId -> learningItems.lockByIdAndUserId(itemId, userId));
        links.deleteAllById(itemIds.stream().map(itemId -> new WordbookLearningItemId(wordbookId, itemId)).toList());
        var deleted = new java.util.ArrayList<UUID>();
        itemIds.forEach(itemId -> {
            if (links.countByIdLearningItemId(itemId) == 0) {
                contributions.deleteUnpublishedLearning(userId, itemId);
                cleanup.scheduleLearningItem(itemId);
                var item = learningItems.findByIdAndUserId(itemId, userId).orElseThrow();
                if (item.personalCustomEntryId != null) privateEntries.delete(userId, item.personalCustomEntryId);
                else learningItems.deleteById(itemId);
                deleted.add(itemId);
            }
        });
        wordbooks.deleteById(wordbookId);
        return new DeletionResult(deleted);
    }

    /** 手动耳词标记属于共享学习身份，保持自动重点和复习进度。 */
    @Transactional
    public LearningItemView setManualEarFocus(UUID userId, UUID itemId, Boolean enabled) {
        if (enabled == null) throw new ResponseStatusException(BAD_REQUEST, "请指定是否加入手动重点");
        var item = learningItems.lockByIdAndUserId(itemId, userId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目不存在"));
        // 手动和自动重点独立，取消手动标记不抹除过去的听力薄弱结果。
        if (item.manualEarFocus != enabled) { item.manualEarFocus = enabled; item.updatedAt = Instant.now(); }
        return learningItemView(item);
    }

    /** 完整重置共享进度，同时清除手动与自动耳词标记。 */
    @Transactional
    public void resetWordbook(UUID userId, UUID wordbookId) {
        requireWordbook(userId, wordbookId);
        links.findByWordbook(wordbookId).stream().map(link -> link.id.learningItemId).sorted()
                .forEach(itemId -> learningItems.lockByIdAndUserId(itemId, userId).ifPresent(item -> {
                    reviews.deleteByLearningItemId(item.id);
                    item.resetProgress();
                }));
    }

    /** 按账户和单词本校验归属，避免客户端用其他账户的 UUID 读写数据。 */
    private Wordbook requireWordbook(UUID userId, UUID wordbookId) {
        return wordbooks.findByIdAndUserId(wordbookId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "单词本不存在"));
    }

    /** 单词本摘要统一从实体转换，隐藏 JPA 版本字段。 */
    private WordbookView wordbookView(Wordbook wordbook) {
        return new WordbookView(wordbook.id, wordbook.name, wordbook.description,
                links.countByIdWordbookId(wordbook.id), wordbook.createdAt, wordbook.updatedAt);
    }

    /** 学习视图合并词典身份和进度存储，封禁词条只显示身份不读取内容。 */
    private LearningItemView learningItemView(UserLearningItem item) {
        var reference = item.personalCustomEntryId == null ? dictionary.learningReference(item.dictionaryEntryId)
            : privateEntries.reference(item.userId, item.personalCustomEntryId);
        var now = Instant.now();
        return new LearningItemView(item.id, item.dictionaryEntryId, reference.written(), reference.languageCode(),
                reference.status(), reference.currentRevision(), item.manualEarFocus, item.progressEpoch,
                item.fsrsAlgorithmVersion, item.reviewCount, item.lapseCount, item.lastReviewedAt,
                item.nextReviewAt, item.nextReviewAt == null || !item.nextReviewAt.isAfter(now),
                item.lastReviewedAt == null ? "0" : item.lastReviewedAt.toString(), item.lastReviewEventId,
                item.automaticEarFocus, item.fsrsState, scheduler.profile(), personal.revision(item.id), item.personalCustomEntryId, personal.audioRevision(item.id));
    }

    /** 字符串字段统一执行长度限制，名称额外要求非空。 */
    private String required(String value, int max, String name) {
        var result = text(value, max, name);
        if (result.isBlank()) throw new ResponseStatusException(BAD_REQUEST, name + "不能为空");
        return result;
    }

    private String text(String value, int max, String name) {
        var result = value == null ? "" : value.trim();
        if (result.length() > max) throw new ResponseStatusException(BAD_REQUEST, name + "超过长度限制");
        return result;
    }

    /** 创建单词本请求。 */
    public record CreateWordbook(String name, String description) {}
    /** 返回真正被删除的学习身份，客户端不会因移除一个关联误清其他单词本共享进度。 */
    public record DeletionResult(List<UUID> deletedLearningItemIds) {}
    /** 本机持有的稳定身份，不接受客户端自报的账户归属。 */
    public record ReconcileRequest(List<UUID> learningItemIds, List<UUID> wordbookIds) {}
    /** 单词本的当前分类关联，用于撤销被另一设备移除的缓存引用。 */
    public record BookState(WordbookView book, List<UUID> learningItemIds) {}
    /** 明确列出请求中仍存在与不存在的身份，客户端不能凭通用 404 猜测删除。 */
    public record ReconcileView(UUID userId, List<LearningItemView> items, List<UUID> missingItemIds,
                                List<BookState> books, List<UUID> missingBookIds) {}
    /** 单词本列表摘要。 */
    public record WordbookView(UUID id, String name, String description, long itemCount,
                               Instant createdAt, Instant updatedAt) {}
    /** 单词本中的学习条目及当前调度状态。 */
    public record LearningItemView(UUID id, UUID dictionaryEntryId, String written, String languageCode,
                                   String status, int currentRevision, boolean manualEarFocus,
                                   UUID progressEpoch, String fsrsAlgorithmVersion, int reviewCount,
                                   int lapseCount, Instant lastReviewedAt, Instant nextReviewAt, boolean due,
                                   String progressVersion, UUID lastReviewEventId, boolean automaticEarFocus,
                                   String fsrsState, FsrsScheduler.Profile scheduler, long personalContentRevision, UUID personalCustomEntryId, long personalAudioRevision) {}
}
