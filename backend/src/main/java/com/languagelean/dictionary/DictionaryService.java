package com.languagelean.dictionary;

import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import com.languagelean.languages.LanguageAdminService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

/** 词典事务边界：稳定身份、独立草稿、原子发布、追加历史和封禁隔离。 */
@Service
public class DictionaryService {
    private final DictionaryEntryRepository entries;
    private final DictionaryRevisionRepository revisions;
    private final LanguageAdminService languages;
    private final ObjectMapper json;
    private final org.springframework.context.ApplicationEventPublisher events;

    DictionaryService(DictionaryEntryRepository entries, DictionaryRevisionRepository revisions,
                      LanguageAdminService languages, ObjectMapper json, org.springframework.context.ApplicationEventPublisher events) {
        this.entries = entries;
        this.revisions = revisions;
        this.languages = languages;
        this.json = json;
        this.events = events;
    }

    /** 后台能搜索全部状态；用户只能搜索启用语言下已经公开过的词条。 */
    @Transactional(readOnly = true)
    public Results<Row> list(boolean admin, String query, String language, int page) {
        var enabled = languages.list().stream().filter(LanguageAdminService.View::enabled)
                .map(LanguageAdminService.View::code).toList();
        String search = query == null ? "" : Normalizer.normalize(query, Normalizer.Form.NFKC).strip();
        if (search.length() > 200) throw bad("搜索内容不能超过 200 字");
        var results = entries.findAll((root, cq, cb) -> {
            var conditions = new ArrayList<Predicate>();
            if (!admin) {
                conditions.add(cb.greaterThan(root.get("currentRevision"), 0));
                conditions.add(enabled.isEmpty() ? cb.disjunction() : root.get("languageCode").in(enabled));
            }
            if (language != null && !language.isBlank()) conditions.add(cb.equal(root.get("languageCode"), language));
            if (!search.isEmpty()) {
                String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                var writtenMatch = cb.like(root.get("normalizedWrittenKey"), "%" + escaped + "%", '\\');
                var publishedMatch = cb.like(root.get("publishedReadingsSearch"), "%" + escaped + "%", '\\');
                conditions.add(admin ? cb.or(writtenMatch, publishedMatch,
                        cb.and(cb.isNotNull(root.get("draftContent")), cb.like(root.get("draftReadingsSearch"), "%" + escaped + "%", '\\')))
                        : cb.or(writtenMatch, publishedMatch));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        }, page(page, Sort.by("written").and(Sort.by("id"))));
        return new Results<>(results.getContent().stream().map(e -> new Row(e.id, e.languageCode, e.written,
                e.status, e.originType, e.currentRevision, admin && e.draftContent != null)).toList(), results.getTotalElements(), page);
    }

    /** 个人CSV仅按固定写法解析已公开身份，不将模糊结果或管理员草稿当作引用。 */
    @Transactional(readOnly = true)
    public Optional<PublicView> exactReference(String language, String script, String written) {
        return entries.findByLanguageCodeAndScriptCodeAndNormalizedWrittenKey(language, script, normalizedKey(written))
                .filter(entry -> entry.currentRevision > 0).map(entry -> detail(entry.id));
    }

    /** 新建草稿保留固定写法；数据库唯一键最终阻止并发创建重复身份。 */
    @Transactional
    public AdminView create(Create request, UUID actor) {
        request = prepare(request, false);
        var entry = DictionaryEntry.create(request.languageCode(), request.scriptCode(), request.written(),
                normalizedKey(request.written()), encode(request.content()), readingsSearch(request.content()), actor);
        entry = entries.saveAndFlush(entry);
        events.publishEvent(new DictionaryContentChanged(entry.id, true));
        return adminView(entry);
    }

    /** 导入预览和正式发布共用手工录入的完整校验，避免两条内容规则分叉。 */
    Create prepare(Create request, boolean publishing) {
        if (request == null) throw bad("请填写词条");
        var written = text(request.written(), 200, true, "单词");
        var language = text(request.languageCode(), 16, true, "语言");
        var script = text(request.scriptCode(), 4, true, "书写系统");
        if (!script.matches("[A-Z][a-z]{3}")) throw bad("书写系统应使用 Jpan、Latn 等四字母代码");
        languages.requireLanguage(language, publishing);
        normalizedKey(written);
        validate(request.content(), publishing);
        return new Create(language, script, written, request.content());
    }

    /** 规范化写法只在这一处生成，手工录入与所有导入适配器使用同一唯一键。 */
    String normalizedKey(String written) {
        var key = Normalizer.normalize(written.trim(), Normalizer.Form.NFKC);
        if (key.length() > 200) throw bad("规范化后的单词过长");
        return key;
    }

    /** 读音与写法使用相同的NFKC包含匹配；换行分隔不同读音，不索引释义和发音脚本。 */
    private String readingsSearch(DictionaryContent content) {
        return content.readings().stream().map(reading -> Normalizer.normalize(
                reading.reading() == null ? "" : reading.reading(), Normalizer.Form.NFKC).strip())
                .filter(value -> !value.isEmpty()).distinct().collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 预览时明确报告已存在，确认时再次调用以处理预览后的并发新增。 */
    boolean exists(Create request) {
        return entries.existsByLanguageCodeAndScriptCodeAndNormalizedWrittenKey(request.languageCode(),
                request.scriptCode(), normalizedKey(request.written()));
    }

    /** 将一个已审核的开源暂存项直接写成第一版公开快照；返回 false 表示确认时已存在。 */
    boolean importPublished(Create original, UUID actor, String note) {
        var request = prepare(original, true);
        if (exists(request)) return false;
        var entry = DictionaryEntry.create(request.languageCode(), request.scriptCode(), request.written(),
                normalizedKey(request.written()), encode(request.content()), readingsSearch(request.content()), actor);
        entry.markOpenSource();
        // UUID 在 persist 前已生成，Spring Data 可能走 merge；后续必须操作其返回的托管实例。
        entry = entries.saveAndFlush(entry);
        var revision = DictionaryRevision.publish(entry, actor, note);
        revisions.save(revision);
        entry.currentRevision = revision.revisionNumber;
        entry.publishReadings();
        entry.status = "PUBLISHED";
        entry.draftContent = null;
        entry.draftBaseRevision = null;
        entry.touch(actor);
        events.publishEvent(new DictionaryContentChanged(entry.id, false));
        return true;
    }

    /** 投稿发布与审核状态在外层同事务，不能覆写未发布的后台草稿。 */
    @Transactional
    AdminView publishContribution(ContributionSubmission submission, UUID reviewer) {
        DictionaryEntry entry; DictionaryContent content = decode(submission.contentJson);
        if (submission.kind.equals("NEW_ENTRY")) {
            if (entries.existsByLanguageCodeAndScriptCodeAndNormalizedWrittenKey(submission.languageCode, submission.scriptCode, submission.normalizedWrittenKey))
                throw new ResponseStatusException(CONFLICT, "同写法的基准词条已存在，请重新提交补充申请");
            var request = prepare(new Create(submission.languageCode, submission.scriptCode, submission.written, content), true);
            entry = DictionaryEntry.create(request.languageCode(), request.scriptCode(), request.written(), submission.normalizedWrittenKey, encode(content), readingsSearch(content), submission.submittedBy);
            entry.originType = "USER_CONTRIBUTED"; entry = entries.saveAndFlush(entry);
        } else {
            entry = entries.lockById(submission.targetEntryId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "目标词条不存在"));
            if (!entry.status.equals("PUBLISHED")) throw new ResponseStatusException(CONFLICT, "目标词条已封禁或不可用");
            if (!Objects.equals(submission.baseRevision, entry.currentRevision)) throw new ResponseStatusException(CONFLICT, "公开基准版本已变化，请拒绝本申请后重新提交");
            if (entry.draftContent != null) throw new ResponseStatusException(CONFLICT, "后台存在未发布草稿，请先处理草稿再审核");
            if (submission.kind.equals("SUPPLEMENT")) content = supplement(published(entry), content);
            validate(content, true); languages.requireLanguage(entry.languageCode, true);
            entry.saveDraft(encode(content), readingsSearch(content), reviewer);
        }
        var revision = DictionaryRevision.publish(entry, reviewer, "用户贡献 " + submission.id + (submission.reviewNote.isBlank() ? "" : "：" + submission.reviewNote));
        revision.contributionId = submission.id; revision.contributedBy = submission.submittedBy;
        var attribution = decode(submission.contentJson);
        revision.contributionSource = attribution.sourceName(); revision.contributionLicense = attribution.license();
        revisions.saveAndFlush(revision); entry.currentRevision = revision.revisionNumber; entry.publishReadings();
        entry.status = "PUBLISHED"; entry.draftContent = null; entry.draftBaseRevision = null; entry.touch(reviewer); entries.flush();
        events.publishEvent(new DictionaryContentChanged(entry.id, false)); return adminView(entry);
    }

    /** 补充保留原子项及UUID，只追加新的读音/词义/例句；新项重建UUID避免私人ID冲突。 */
    private DictionaryContent supplement(DictionaryContent base, DictionaryContent incoming) {
        var readings = new ArrayList<>(base.readings()); var senses = new ArrayList<>(base.senses());
        for (var r : incoming.readings()) if (readings.stream().noneMatch(old -> same(old.reading(), r.reading()) && same(old.pronunciationText(), r.pronunciationText())))
            readings.add(new DictionaryContent.Reading(UUID.randomUUID(), r.reading(), r.pronunciationText()));
        for (var s : incoming.senses()) {
            int match = -1;
            for (int i = 0; i < senses.size(); i++) if (same(senses.get(i).partOfSpeech(), s.partOfSpeech()) && same(senses.get(i).gloss(), s.gloss()) && same(senses.get(i).glossLanguage(), s.glossLanguage())) { match = i; break; }
            var examples = new ArrayList<DictionaryContent.Example>(match < 0 ? List.of() : senses.get(match).examples());
            for (var e : s.examples()) {
                int existing = -1;
                for (int i = 0; i < examples.size(); i++) {
                    var old = examples.get(i);
                    if (same(old.text(), e.text()) && same(old.pronunciationText(), e.pronunciationText()) && same(old.translation(), e.translation()) && same(old.translationLanguage(), e.translationLanguage())) { existing = i; break; }
                }
                if (existing < 0) examples.add(new DictionaryContent.Example(UUID.randomUUID(), e.text(), e.pronunciationText(), e.translation(), e.translationLanguage(), e.translations(), e.attribution()));
                else {
                    var old = examples.get(existing); var translations = new java.util.LinkedHashMap<>(old.translations());
                    e.translations().forEach(translations::putIfAbsent);
                    examples.set(existing, new DictionaryContent.Example(old.id(), old.text(), old.pronunciationText(), old.translation(), old.translationLanguage(), translations, old.attribution()));
                }
            }
            if (match < 0) senses.add(new DictionaryContent.Sense(UUID.randomUUID(), s.partOfSpeech(), s.gloss(), examples, s.glossLanguage(), s.translations()));
            else {
                var old = senses.get(match); var translations = new java.util.LinkedHashMap<>(old.translations());
                // 补充只能添加尚未存在的语言，不能借此替换已发布译文；改译文走REVISION。
                s.translations().forEach(translations::putIfAbsent);
                senses.set(match, new DictionaryContent.Sense(old.id(), old.partOfSpeech(), old.gloss(), examples, old.glossLanguage(), translations));
            }
        }
        if (readings.equals(base.readings()) && senses.equals(base.senses())) throw new ResponseStatusException(CONFLICT, "提交内容已存在，没有新的补充内容");
        var result = new DictionaryContent(1, readings, senses, base.sourceName(), base.license()); validate(result, true); return result;
    }
    /** 空值、首尾空白及兼容字形规范化后判断正文重复，不改写已有显示文本。 */
    private boolean same(String a, String b) { return Normalizer.normalize(a == null ? "" : a, Normalizer.Form.NFKC).trim().equals(Normalizer.normalize(b == null ? "" : b, Normalizer.Form.NFKC).trim()); }

    /** 读取后台详情时同时显示公开版和草稿，方便管理员核对发布差异。 */
    @Transactional(readOnly = true)
    public AdminView adminDetail(UUID id) { return adminView(find(id)); }

    /** 用户永远不读取草稿、审计信息或封禁词条的内容快照。 */
    @Transactional(readOnly = true)
    public PublicView detail(UUID id) {
        var entry = find(id);
        boolean enabled = languages.list().stream().anyMatch(l -> l.code().equals(entry.languageCode) && l.enabled());
        if (entry.currentRevision == 0 || !enabled) throw new ResponseStatusException(NOT_FOUND, "词条不存在或暂未公开");
        return new PublicView(entry.id, entry.languageCode, entry.scriptCode, entry.written, entry.status, entry.originType,
                entry.currentRevision, entry.status.equals("PUBLISHED") ? published(entry) : null);
    }

    /** 学习域添加词条时只允许启用语言的公开内容，避免把草稿或封禁词条加入新进度。 */
    @Transactional(readOnly = true)
    public LearningReference requireLearningReference(UUID id) {
        var entry = find(id);
        boolean enabled = languages.list().stream().anyMatch(l -> l.code().equals(entry.languageCode) && l.enabled());
        if (entry.currentRevision == 0 || !enabled || !entry.status.equals("PUBLISHED"))
            throw new ResponseStatusException(NOT_FOUND, "词条不存在、暂未公开或已封禁");
        return new LearningReference(entry.id, entry.languageCode, entry.written, entry.status,
                entry.currentRevision, entry.originType);
    }

    /** 学习域读取已有关联时保留封禁状态，用户仍能看到“已封禁”而不是丢失进度身份。 */
    @Transactional(readOnly = true)
    public LearningReference learningReference(UUID id) {
        var entry = find(id);
        return new LearningReference(entry.id, entry.languageCode, entry.written, entry.status,
                entry.currentRevision, entry.originType);
    }

    /** 音频模块解析已授权的资源；不以词条写法或例句正文替代缺失发音。 */
    @Transactional(readOnly = true)
    public List<AudioSource> audioSources(UUID id, boolean draft) {
        DictionaryContent content;
        String language;
        if (draft) {
            var view = adminDetail(id); content = view.draft(); language = view.languageCode();
        } else {
            var view = detail(id); content = view.content(); language = view.languageCode();
        }
        if (content == null) throw new ResponseStatusException(NOT_FOUND, "音频内容不存在或已封禁");
        var sources = new ArrayList<AudioSource>();
        for (var reading : content.readings()) sources.add(new AudioSource(id, reading.id(), language, "WORD", reading.pronunciationText()));
        for (var sense : content.senses()) for (var example : sense.examples())
            sources.add(new AudioSource(id, example.id(), language, "EXAMPLE", example.pronunciationText()));
        return sources;
    }

    /** 一个读音或例句的显式发音输入。 */
    public record AudioSource(UUID entryId, UUID resourceId, String languageCode, String kind, String pronunciationText) {}

    /** 反馈后台需正常展示资源封禁/移除状态；用 Optional 表示不可用，避免捕获代理事务异常后误触回滚。 */
    @Transactional(readOnly = true)
    public Optional<PublishedAudioSource> availableAudioSource(UUID entryId, UUID resourceId, String kind) {
        var entry = entries.findById(entryId).orElse(null);
        if (entry == null || entry.currentRevision == 0 || !entry.status.equals("PUBLISHED")
                || languages.list().stream().noneMatch(l -> l.code().equals(entry.languageCode) && l.enabled())) return Optional.empty();
        var content = published(entry);
        if (kind.equals("WORD")) return content.readings().stream().filter(r -> r.id().equals(resourceId))
                .findFirst().map(r -> new PublishedAudioSource(entry.written, entry.currentRevision, r.pronunciationText()));
        if (kind.equals("EXAMPLE")) return content.senses().stream().flatMap(s -> s.examples().stream()).filter(e -> e.id().equals(resourceId))
                .findFirst().map(e -> new PublishedAudioSource(entry.written, entry.currentRevision, e.pronunciationText()));
        return Optional.empty();
    }
    /** 仅当前公开发音及其版本，不包含草稿、私人内容或管理员审计。 */
    public record PublishedAudioSource(String written, int currentRevision, String pronunciationText) {}

    /** 覆盖草稿必须带编辑版本，不允许陈旧页面静默覆盖最新内容。 */
    @Transactional
    public AdminView save(UUID id, Edit edit, UUID actor) {
        var entry = writable(id, edit.version());
        validate(edit.content(), false);
        entry.saveDraft(encode(edit.content()), readingsSearch(edit.content()), actor);
        entries.flush();
        events.publishEvent(new DictionaryContentChanged(entry.id, true));
        return adminView(entry);
    }

    /** 管理员确认即为本阶段审核；快照插入与当前版本切换在一个事务提交。 */
    @Transactional
    public AdminView publish(UUID id, Action action, UUID actor) {
        var entry = writable(id, action.version());
        if (entry.draftContent == null) throw bad("没有待发布草稿");
        if (!Objects.equals(entry.draftBaseRevision, entry.currentRevision))
            throw new ResponseStatusException(CONFLICT, "草稿基准版本已变化，请重新编辑");
        languages.requireLanguage(entry.languageCode, true);
        validate(decode(entry.draftContent), true);
        var revision = DictionaryRevision.publish(entry, actor, text(action.note(), 500, false, "发布说明"));
        revisions.save(revision);
        entry.currentRevision = revision.revisionNumber;
        entry.publishReadings();
        entry.status = "PUBLISHED";
        entry.draftContent = null;
        entry.draftBaseRevision = null;
        entry.touch(actor);
        entries.flush();
        events.publishEvent(new DictionaryContentChanged(entry.id, false));
        return adminView(entry);
    }

    /** 恢复历史先复制成草稿，必须再次确认发布才会对用户可见。 */
    @Transactional
    public AdminView restore(UUID id, int revision, Action action, UUID actor) {
        var entry = writable(id, action.version());
        var previous = revisions.findByEntryIdAndRevisionNumber(id, revision)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "历史版本不存在"));
        if (entry.draftContent != null) throw new ResponseStatusException(CONFLICT, "请先发布现有草稿，再恢复历史");
        entry.saveDraft(previous.content, readingsSearch(decode(previous.content)), actor);
        entries.flush();
        events.publishEvent(new DictionaryContentChanged(entry.id, true));
        return adminView(entry);
    }

    /** 封禁保留 ID 和历史；当前阶段不提供解除封禁操作。 */
    @Transactional
    public AdminView ban(UUID id, Action action, UUID actor) {
        var entry = writable(id, action.version());
        if (entry.currentRevision == 0) throw bad("尚未发布的词条无需封禁");
        entry.status = "BANNED";
        entry.touch(actor);
        entries.flush();
        return adminView(entry);
    }

    /** 后台历史按倒序分页，完整快照始终归属于路径指定词条。 */
    @Transactional(readOnly = true)
    public Results<History> history(UUID id, int page) {
        find(id);
        var result = revisions.findByEntryIdOrderByRevisionNumberDesc(id, page(page, Sort.unsorted()));
        return new Results<>(result.stream().map(r -> new History(r.revisionNumber, decode(r.content),
                r.publishedBy, r.publishedAt, r.note, r.contributionId, r.contributedBy, r.contributionSource, r.contributionLicense)).toList(), result.getTotalElements(), page);
    }

    /** 写操作先加行锁，再校验客户端看到的版本和封禁边界。 */
    private DictionaryEntry writable(UUID id, Long version) {
        var entry = entries.lockById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "词条不存在"));
        if (version == null || version != entry.version) throw new ResponseStatusException(CONFLICT, "词条已被修改，请重新打开后编辑");
        if (entry.status.equals("BANNED")) throw bad("已封禁词条不可编辑或发布");
        return entry;
    }

    /** 缺失词条统一返回 404。 */
    private DictionaryEntry find(UUID id) {
        return entries.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "词条不存在"));
    }
    /** 只从历史表解析当前公开内容，不从草稿推断。 */
    private DictionaryContent published(DictionaryEntry entry) {
        return entry.currentRevision == 0 ? null : decode(revisions
                .findByEntryIdAndRevisionNumber(entry.id, entry.currentRevision).orElseThrow().content);
    }
    /** 当前编辑版本用于下一次保存、发布或封禁请求。 */
    private AdminView adminView(DictionaryEntry entry) {
        return new AdminView(entry.id, entry.languageCode, entry.scriptCode, entry.written, entry.status, entry.originType,
                entry.currentRevision, entry.version, published(entry),
                entry.draftContent == null ? null : decode(entry.draftContent));
    }
    /** 明确限制分页大小和页码，避免一次读取全部历史。 */
    private Pageable page(int index, Sort sort) {
        if (index < 0 || index > 100000) throw bad("页码无效");
        return PageRequest.of(index, 20, sort);
    }
    /** JSON 字段只接受有明确版本和类型的内容对象。 */
    private String encode(DictionaryContent content) { return json.writeValueAsString(content); }
    /** 发布历史和草稿使用同一份可演进契约。 */
    private DictionaryContent decode(String content) { return json.readValue(content, DictionaryContent.class); }

    /** 私有内容复用结构校验，允许不完整，不会创建或修改公开词典。 */
    public void validatePersonalContent(DictionaryContent content) { validate(content, false); }

    /** 草稿可不完整；发布至少有一条释义，无读音仍可整理但不具备听力资源。 */
    private void validate(DictionaryContent content, boolean publishing) {
        if (content == null || content.schemaVersion() != 1 || content.readings() == null || content.senses() == null)
            throw bad("词条内容格式或版本不正确");
        if (content.readings().size() > 20 || content.senses().size() > 50) throw bad("读音最多 20 个，词义最多 50 个");
        var ids = new HashSet<UUID>();
        for (var reading : content.readings()) {
            if (reading == null) throw bad("读音不能为空");
            unique(ids, reading.id());
            text(reading.reading(), 200, publishing, "读音");
            text(reading.pronunciationText(), 500, false, "词条发音");
        }
        for (var sense : content.senses()) {
            if (sense == null || sense.examples() == null || sense.examples().size() > 20) throw bad("词义格式错误或例句超过 20 条");
            unique(ids, sense.id());
            text(sense.partOfSpeech(), 100, false, "词性");
            text(sense.gloss(), 4000, publishing, "释义");
            translationLanguage(sense.glossLanguage()); translations(sense.translations(), 4000);
            for (var example : sense.examples()) {
                if (example == null) throw bad("例句不能为空");
                unique(ids, example.id());
                text(example.text(), 2000, publishing, "例句");
                text(example.pronunciationText(), 2000, false, "例句发音");
                text(example.translation(), 2000, false, "例句译文");
                translationLanguage(example.translationLanguage()); translations(example.translations(), 2000);
                if (example.attribution() != null) {
                    var a = example.attribution();
                    text(a.sourceName(), 200, true, "例句来源"); text(a.license(), 200, true, "例句许可");
                    sourceUrl(a.sourceUrl()); text(a.sourceId(), 200, false, "来源句子ID"); text(a.author(), 200, false, "例句作者");
                }
            }
        }
        if (publishing && content.senses().isEmpty()) throw bad("发布时至少填写一条释义");
        text(content.sourceName(), 200, publishing, "来源");
        text(content.license(), 200, false, "许可");
    }
    /** 可选BCP 47语言标签不猜测译文语言；所有新增字段也接受旧快照的空值。 */
    private void translationLanguage(String language) {
        if (language != null && !language.isEmpty() && !language.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}")) throw bad("译文语言代码格式不正确");
        text(language, 35, false, "译文语言");
    }
    /** 限制译文大小并核查独立来源，防止上传绕过正文限制。 */
    private void translations(java.util.Map<String, DictionaryContent.Translation> values, int max) {
        if (values.size() > 16) throw bad("每项最多保存16种译文");
        for (var value : values.entrySet()) {
            if (value.getKey() == null || value.getKey().isBlank() || value.getValue() == null) throw bad("译文语言和内容不能为空");
            translationLanguage(value.getKey()); var t = value.getValue();
            text(t.text(), max, true, "译文"); text(t.sourceName(), 200, true, "译文来源");
            text(t.license(), 200, false, "译文许可"); sourceUrl(t.sourceUrl());
            text(t.author(), 200, false, "译文作者");
        }
    }
    /** 页面只展示安全的HTTPS出处链接。 */
    private void sourceUrl(String value) {
        text(value, 1000, false, "来源链接");
        if (value != null && !value.isBlank()) {
            try { var uri = java.net.URI.create(value); if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw bad("来源链接必须为HTTPS地址"); }
            catch (IllegalArgumentException e) { throw bad("来源链接格式不正确"); }
        }
    }
    /** 子项稳定 ID 在同一版本内不能重复，防止后续资源关联歧义。 */
    private void unique(Set<UUID> ids, UUID id) {
        if (id == null || !ids.add(id)) throw bad("读音、词义和例句必须具有不同的稳定 ID");
    }
    /** 统一字符串长度和必填检查，不把空白当成有效释义。 */
    private String text(String value, int max, boolean required, String name) {
        if (value == null) value = "";
        if (value.length() > max || required && value.isBlank()) throw bad(name + "不能为空或超过长度限制");
        return value.trim();
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }

    /** 管理员新建基准词条请求。 */
    public record Create(String languageCode, String scriptCode, String written, DictionaryContent content) {}
    /** 草稿保存请求，version 是读取时的编辑版本。 */
    public record Edit(Long version, DictionaryContent content) {}
    /** 发布、封禁、历史恢复均须携带预期编辑版本。 */
    public record Action(Long version, String note) {}
    /** 分页列表只包含身份与状态。 */
    public record Row(UUID id, String languageCode, String written, String status, String originType,
                      int currentRevision, boolean hasDraft) {}
    /** 稳定分页响应，避免泄露框架内部 Page 序列化结构。 */
    public record Results<T>(List<T> items, long total, int page) {}
    /** 普通用户详情，封禁时 content 为空。 */
    public record PublicView(UUID id, String languageCode, String scriptCode, String written, String status,
                             String originType,
                             int currentRevision, DictionaryContent content) {}
    /** 学习域使用的词典身份摘要；不跨域暴露 JPA 实体。 */
    public record LearningReference(UUID id, String languageCode, String written, String status,
                                    int currentRevision, String originType) {}
    /** 管理员详情保留独立的已发布内容和草稿。 */
    public record AdminView(UUID id, String languageCode, String scriptCode, String written, String status,
                            String originType,
                            int currentRevision, long version, DictionaryContent published, DictionaryContent draft) {}
    /** 带发布人、时间和说明的只读历史快照。 */
    public record History(int revision, DictionaryContent content, UUID publishedBy, Instant publishedAt, String note,
                          UUID contributionId, UUID contributedBy, String contributionSource, String contributionLicense) {}
}
