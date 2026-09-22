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

    DictionaryService(DictionaryEntryRepository entries, DictionaryRevisionRepository revisions,
                      LanguageAdminService languages, ObjectMapper json) {
        this.entries = entries;
        this.revisions = revisions;
        this.languages = languages;
        this.json = json;
    }

    /** 后台能搜索全部状态；用户只能搜索启用语言下已经公开过的词条。 */
    @Transactional(readOnly = true)
    public Results<Row> list(boolean admin, String query, String language, int page) {
        var enabled = languages.list().stream().filter(LanguageAdminService.View::enabled)
                .map(LanguageAdminService.View::code).toList();
        String search = query == null ? "" : Normalizer.normalize(query.trim(), Normalizer.Form.NFKC);
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
                conditions.add(cb.like(root.get("normalizedWrittenKey"), "%" + escaped + "%", '\\'));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        }, page(page, Sort.by("written").and(Sort.by("id"))));
        return new Results<>(results.getContent().stream().map(e -> new Row(e.id, e.languageCode, e.written,
                e.status, e.currentRevision, admin && e.draftContent != null)).toList(), results.getTotalElements(), page);
    }

    /** 新建草稿保留固定写法；数据库唯一键最终阻止并发创建重复身份。 */
    @Transactional
    public AdminView create(Create request, UUID actor) {
        if (request == null) throw bad("请填写词条");
        var written = text(request.written(), 200, true, "单词");
        var language = text(request.languageCode(), 16, true, "语言");
        var script = text(request.scriptCode(), 4, true, "书写系统");
        if (!script.matches("[A-Z][a-z]{3}")) throw bad("书写系统应使用 Jpan、Latn 等四字母代码");
        languages.requireLanguage(language, false);
        var key = Normalizer.normalize(written, Normalizer.Form.NFKC);
        if (key.length() > 200) throw bad("规范化后的单词过长");
        validate(request.content(), false);
        var entry = DictionaryEntry.create(language, script, written, key, encode(request.content()), actor);
        entries.saveAndFlush(entry);
        return adminView(entry);
    }

    /** 读取后台详情时同时显示公开版和草稿，方便管理员核对发布差异。 */
    @Transactional(readOnly = true)
    public AdminView adminDetail(UUID id) { return adminView(find(id)); }

    /** 用户永远不读取草稿、审计信息或封禁词条的内容快照。 */
    @Transactional(readOnly = true)
    public PublicView detail(UUID id) {
        var entry = find(id);
        boolean enabled = languages.list().stream().anyMatch(l -> l.code().equals(entry.languageCode) && l.enabled());
        if (entry.currentRevision == 0 || !enabled) throw new ResponseStatusException(NOT_FOUND, "词条不存在或暂未公开");
        return new PublicView(entry.id, entry.languageCode, entry.scriptCode, entry.written, entry.status,
                entry.currentRevision, entry.status.equals("PUBLISHED") ? published(entry) : null);
    }

    /** 覆盖草稿必须带编辑版本，不允许陈旧页面静默覆盖最新内容。 */
    @Transactional
    public AdminView save(UUID id, Edit edit, UUID actor) {
        var entry = writable(id, edit.version());
        validate(edit.content(), false);
        entry.saveDraft(encode(edit.content()), actor);
        entries.flush();
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
        entry.status = "PUBLISHED";
        entry.draftContent = null;
        entry.draftBaseRevision = null;
        entry.touch(actor);
        entries.flush();
        return adminView(entry);
    }

    /** 恢复历史先复制成草稿，必须再次确认发布才会对用户可见。 */
    @Transactional
    public AdminView restore(UUID id, int revision, Action action, UUID actor) {
        var entry = writable(id, action.version());
        var previous = revisions.findByEntryIdAndRevisionNumber(id, revision)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "历史版本不存在"));
        if (entry.draftContent != null) throw new ResponseStatusException(CONFLICT, "请先发布现有草稿，再恢复历史");
        entry.saveDraft(previous.content, actor);
        entries.flush();
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
                r.publishedBy, r.publishedAt, r.note)).toList(), result.getTotalElements(), page);
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
        return new AdminView(entry.id, entry.languageCode, entry.scriptCode, entry.written, entry.status,
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
            for (var example : sense.examples()) {
                if (example == null) throw bad("例句不能为空");
                unique(ids, example.id());
                text(example.text(), 2000, publishing, "例句");
                text(example.pronunciationText(), 2000, false, "例句发音");
                text(example.translation(), 2000, false, "例句译文");
            }
        }
        if (publishing && content.senses().isEmpty()) throw bad("发布时至少填写一条释义");
        text(content.sourceName(), 200, false, "来源");
        text(content.license(), 200, false, "许可");
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
    public record Row(UUID id, String languageCode, String written, String status, int currentRevision, boolean hasDraft) {}
    /** 稳定分页响应，避免泄露框架内部 Page 序列化结构。 */
    public record Results<T>(List<T> items, long total, int page) {}
    /** 普通用户详情，封禁时 content 为空。 */
    public record PublicView(UUID id, String languageCode, String scriptCode, String written, String status,
                             int currentRevision, DictionaryContent content) {}
    /** 管理员详情保留独立的已发布内容和草稿。 */
    public record AdminView(UUID id, String languageCode, String scriptCode, String written, String status,
                            int currentRevision, long version, DictionaryContent published, DictionaryContent draft) {}
    /** 带发布人、时间和说明的只读历史快照。 */
    public record History(int revision, DictionaryContent content, UUID publishedBy, Instant publishedAt, String note) {}
}
