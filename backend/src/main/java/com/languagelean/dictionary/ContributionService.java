package com.languagelean.dictionary;

import com.languagelean.learning.PrivateEntryService;
import com.languagelean.learning.PersonalContentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

/** 投稿事务边界：冻结明确提交的内容、检查来源版本、原子审核发布，私人学习身份不迁移。 */
@Service
public class ContributionService {
    private final ContributionRepository submissions;
    private final DictionaryEntryRepository entries;
    private final DictionaryRevisionRepository revisions;
    private final DictionaryService dictionary;
    private final PrivateEntryService privateEntries;
    private final PersonalContentService personal;
    private final ObjectMapper json;

    ContributionService(ContributionRepository submissions, DictionaryEntryRepository entries, DictionaryService dictionary,
            PrivateEntryService privateEntries, PersonalContentService personal, ObjectMapper json, DictionaryRevisionRepository revisions) {
        this.submissions = submissions; this.entries = entries; this.dictionary = dictionary;
        this.privateEntries = privateEntries; this.personal = personal; this.json = json;
        this.revisions = revisions;
    }
    /** 普通用户限定本人；管理员列表也只传摘要，不批量传私人正文。 */
    @Transactional(readOnly = true)
    public DictionaryService.Results<Row> list(UUID owner, String status, int page) {
        if (page < 0 || page > 100000) throw bad("页码无效");
        if (status != null && !status.isBlank() && !Set.of("PENDING_REVIEW", "APPROVED", "REJECTED", "WITHDRAWN").contains(status)) throw bad("投稿状态无效");
        var result = submissions.findAll((root, query, cb) -> {
            var conditions = new ArrayList<Predicate>();
            if (owner != null) conditions.add(cb.equal(root.get("submittedBy"), owner));
            if (status != null && !status.isBlank()) conditions.add(cb.equal(root.get("status"), status));
            return cb.and(conditions.toArray(Predicate[]::new));
        }, PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new DictionaryService.Results<>(result.stream().map(this::row).toList(), result.getTotalElements(), page);
    }
    /** 详情只能由本人或管理员专用路由读取，未知和他人身份统一404。 */
    @Transactional(readOnly = true)
    public View detail(UUID owner, UUID id) {
        var value = owner == null ? submissions.findById(id) : submissions.findByIdAndSubmittedBy(id, owner);
        return view(value.orElseThrow(() -> missing()));
    }
    /** 网络重试先核对原请求指纹；确认来源版本后冻结用户选择的正文，不自动复制笔记。 */
    @Transactional
    public View submit(UUID owner, Submit request) {
        if (request == null || request.id() == null) throw bad("请提供投稿请求身份");
        String hash = hash(request);
        var previous = previous(owner, request.id(), hash);
        if (previous != null) return previous;
        if (!Boolean.TRUE.equals(request.publishRequested())) throw bad("请明确申请审核后公开本次快照");
        if ((request.privateEntryId() == null) == (request.learningItemId() == null)) throw bad("只能选择一个投稿来源");
        var submission = new ContributionSubmission(); submission.id = request.id(); submission.submittedBy = owner;
        DictionaryService.PublicView base = null;
        if (request.privateEntryId() != null) {
            var source = privateEntries.contributionSource(owner, request.privateEntryId());
            previous = previous(owner, request.id(), hash); if (previous != null) return previous;
            if (request.privateVersion() == null || request.privateVersion() != source.version()) throw conflict("私有词条版本已变化，请重新打开投稿表单");
            submission.privateEntryId = source.id(); submission.sourceId = source.id(); submission.sourceKind = "PRIVATE";
            submission.sourceVersion = source.version(); submission.languageCode = source.languageCode(); submission.scriptCode = source.scriptCode(); submission.written = source.written();
            var target = entries.findByLanguageCodeAndScriptCodeAndNormalizedWrittenKey(source.languageCode(), source.scriptCode(), dictionary.normalizedKey(source.written())).orElse(null);
            submission.kind = target == null ? "NEW_ENTRY" : "SUPPLEMENT";
            if (target != null) {
                if (!target.status.equals("PUBLISHED")) throw conflict("同写法的基准词条已存在但暂不可用");
                base = dictionary.detail(target.id);
            }
        } else {
            var source = personal.contributionSource(owner, request.learningItemId());
            previous = previous(owner, request.id(), hash); if (previous != null) return previous;
            if (source.entry().originType().equals("PRIVATE")) throw bad("独立私有词条请使用私有词条投稿入口");
            if (!source.entry().status().equals("PUBLISHED")) throw conflict("目标词条已封禁或不可用");
            if (request.personalRevision() == null || request.personalRevision() != source.personal().revision()
                    || !Objects.equals(request.baseRevision(), source.entry().currentRevision())) throw conflict("个人内容或公开版本已变化，请重新打开投稿表单");
            base = dictionary.detail(source.entry().id()); submission.kind = "REVISION";
            submission.learningItemId = source.learningItemId(); submission.sourceId = source.learningItemId(); submission.sourceKind = "LEARNING";
            submission.sourceVersion = source.personal().revision(); submission.languageCode = base.languageCode(); submission.scriptCode = base.scriptCode(); submission.written = base.written();
        }
        dictionary.prepare(new DictionaryService.Create(submission.languageCode, submission.scriptCode, submission.written, request.content()), true);
        submission.contentJson = json.writeValueAsString(normalize(request.content()));
        submission.normalizedWrittenKey = dictionary.normalizedKey(submission.written);
        if (base != null) { submission.targetEntryId = base.id(); submission.baseRevision = base.currentRevision(); submission.baseContentJson = json.writeValueAsString(base.content()); }
        submission.activeSourceKey = owner + ":" + submission.sourceKind + ":" + submission.sourceId;
        if (submissions.existsByActiveSourceKey(submission.activeSourceKey)) throw conflict("这个来源已有待审投稿，请先撤回或等待审核");
        submission.requestHash = hash; submission.status = "PENDING_REVIEW"; submission.publishRequested = true;
        submission.submitNote = text(request.note(), 500); submission.reviewNote = ""; submission.createdAt = Instant.now();
        return view(submissions.saveAndFlush(submission));
    }
    /** 行锁与预期版本防止重复审核；发布历史和审核终态在同一事务提交。 */
    @Transactional
    public View approve(UUID reviewer, UUID id, Action action) {
        var submission = pending(id, action); submission.reviewNote = text(action.note(), 400);
        var published = dictionary.publishContribution(submission, reviewer);
        submission.publishedEntryId = published.id(); submission.publishedRevision = published.currentRevision();
        finish(submission, "APPROVED", reviewer); return view(submissions.saveAndFlush(submission));
    }
    /** 拒绝必须填写理由，原公开版本和私人来源都不变。 */
    @Transactional
    public View reject(UUID reviewer, UUID id, Action action) {
        var submission = pending(id, action); submission.reviewNote = text(action.note(), 400);
        if (submission.reviewNote.isBlank()) throw bad("拒绝时请填写原因");
        finish(submission, "REJECTED", reviewer); return view(submissions.saveAndFlush(submission));
    }
    /** 本人可撤回待审申请；不能撤销已经公开的历史。 */
    @Transactional
    public View withdraw(UUID owner, UUID id, Action action) {
        var owned = submissions.findByIdAndSubmittedBy(id, owner).orElseThrow(() -> missing());
        var submission = pending(owned.id, action); submission.reviewNote = "本人撤回";
        finish(submission, "WITHDRAWN", owner); return view(submissions.saveAndFlush(submission));
    }
    /** 公开详情只暴露已发布来源和许可，不包含私人来源身份或未审核快照。 */
    @Transactional(readOnly = true)
    public List<Credit> credits(UUID entryId) {
        if (!dictionary.detail(entryId).status().equals("PUBLISHED")) throw missing();
        return revisions.findTop20ByEntryIdAndContributionIdIsNotNullOrderByRevisionNumberDesc(entryId).stream()
            .map(r -> new Credit(r.contributionSource, r.contributionLicense, r.revisionNumber, r.publishedAt, r.contributorDeleted)).toList();
    }
    /** 快照子项中的可选字符串统一为空文本，前端和离线缓存无需处理null。 */
    private DictionaryContent normalize(DictionaryContent c) {
        return new DictionaryContent(1, c.readings().stream().map(r -> new DictionaryContent.Reading(r.id(), text(r.reading(), 200), text(r.pronunciationText(), 500))).toList(),
            c.senses().stream().map(s -> new DictionaryContent.Sense(s.id(), text(s.partOfSpeech(), 100), text(s.gloss(), 4000), s.examples().stream().map(e ->
                new DictionaryContent.Example(e.id(), text(e.text(), 2000), text(e.pronunciationText(), 2000), text(e.translation(), 2000), e.translationLanguage(), e.translations(), e.attribution())).toList(), s.glossLanguage(), s.translations())).toList(), text(c.sourceName(), 200), text(c.license(), 200));
    }
    /** 终态申请不再占用来源的待审名额。 */
    private void finish(ContributionSubmission s, String status, UUID actor) { s.status = status; s.reviewedBy = actor; s.reviewedAt = Instant.now(); s.activeSourceKey = null; }
    /** 审核操作先锁行，再检查表单版本及待审状态。 */
    private ContributionSubmission pending(UUID id, Action action) {
        var row = submissions.lockById(id).orElseThrow(() -> missing());
        if (action == null || action.version() == null || action.version() != row.version || !row.status.equals("PENDING_REVIEW")) throw conflict("申请已变化，请重新读取后处理");
        return row;
    }
    /** 只读列表摘要。 */
    private Row row(ContributionSubmission s) { return new Row(s.id, s.written, s.languageCode, s.kind, s.status, s.createdAt, s.publishedEntryId, s.publishedRevision); }
    /** 投稿及基准正文均取冻结快照，不读取当前私人笔记。 */
    private View view(ContributionSubmission s) {
        return new View(row(s), s.scriptCode, s.version, s.baseRevision, decode(s.contentJson), s.baseContentJson == null ? null : decode(s.baseContentJson),
            s.submitNote, s.reviewNote, s.reviewedAt);
    }
    /** 内容快照遵循词典的版本化结构。 */
    private DictionaryContent decode(String content) { return json.readValue(content, DictionaryContent.class); }
    /** 来源行锁后再次检查幂等身份，让同时发送的同一请求也返回原申请。 */
    private View previous(UUID owner, UUID id, String hash) {
        var row = submissions.findById(id).orElse(null);
        if (row == null) return null;
        if (!row.submittedBy.equals(owner)) throw missing();
        if (!row.requestHash.equals(hash)) throw conflict("请求身份已被其他内容使用，请重新提交");
        return view(row);
    }
    /** 请求指纹不依赖提交后变化的来源版本。 */
    private String hash(Submit request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 可选文本清空、长度检查，避免长审核意见超出发布备注限制。 */
    private String text(String value, int max) { if (value == null) return ""; if (value.length() > max) throw bad("文本超过" + max + "字限制"); return value.trim(); }
    private ResponseStatusException missing() { return new ResponseStatusException(NOT_FOUND, "投稿不存在或不可用"); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(CONFLICT, message); }
    /** 正文由用户显式确认；来源身份和版本用于防止错账号和陈旧提交。 */
    public record Submit(UUID id, UUID privateEntryId, Long privateVersion, UUID learningItemId, Long personalRevision, Integer baseRevision,
                         DictionaryContent content, String note, Boolean publishRequested) {}
    /** 每次终态决定携带用户读到的审核版本。 */
    public record Action(Long version, String note) {}
    /** 本人和后台分页共用的摘要。 */
    public record Row(UUID id, String written, String languageCode, String kind, String status, Instant createdAt, UUID publishedEntryId, Integer publishedRevision) {}
    /** 详情只包含明确投稿的内容和当时的公开基准。 */
    public record View(Row row, String scriptCode, long version, Integer baseRevision, DictionaryContent content, DictionaryContent baseContent,
                       String submitNote, String reviewNote, Instant reviewedAt) {}
    /** 可公开的贡献来源，不暴露投稿人账号和私人来源ID。 */
    public record Credit(String sourceName, String license, int revision, Instant publishedAt, boolean contributorDeleted) {}
}
