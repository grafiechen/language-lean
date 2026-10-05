package com.languagelean.learning;
import com.languagelean.dictionary.*;
import com.languagelean.audio.AudioCleanupService;
import com.languagelean.languages.LanguageAdminService;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

/** 私有词条的归属、内容和去重边界；发布贡献在后续独立审核用例中处理。 */
@Service
public class PrivateEntryService {
    private final PrivateEntryRepository entries;
    private final DictionaryService dictionary;
    private final LanguageAdminService languages;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final AudioCleanupService cleanup;
    private final PrivateEntryChangeRepository changes;
    private final ContributionRepository contributions;
    PrivateEntryService(PrivateEntryRepository entries, DictionaryService dictionary, LanguageAdminService languages,
            ObjectMapper json, ApplicationEventPublisher events, AudioCleanupService cleanup, PrivateEntryChangeRepository changes, ContributionRepository contributions) {
        this.entries = entries; this.dictionary = dictionary; this.languages = languages;
        this.json = json; this.events = events; this.cleanup = cleanup; this.changes = changes;
        this.contributions = contributions;
    }
    /** 只返回本账户的分页结果，搜索写法也使用NFKC规范化。 */
    @Transactional(readOnly = true)
    public DictionaryService.Results<View> list(UUID userId, String query, int page) {
        if (page < 0 || page > 100000) throw new ResponseStatusException(BAD_REQUEST, "页码无效");
        var result = entries.search(userId, key(query == null ? "" : query, false), PageRequest.of(page, 20, Sort.by("createdAt").descending().and(Sort.by("id"))));
        return new DictionaryService.Results<>(result.stream().map(this::view).toList(), result.getTotalElements(), page);
    }
    /** 按账户读取详情，管理员角色不改变私人内容归属。 */
    @Transactional(readOnly = true)
    public View detail(UUID userId, UUID id) { return view(owned(userId, id)); }
    /** 可只填写法创建；重复提示已存在，不覆写旧词条或进度。 */
    @Transactional
    public View create(UUID userId, Create request) {
        if (request == null) throw new ResponseStatusException(BAD_REQUEST, "请填写私有词条");
        if (request.languageCode() == null || request.languageCode().length() > 16) throw new ResponseStatusException(BAD_REQUEST, "请选择语言");
        languages.requireLanguage(request.languageCode(), true);
        if (request.scriptCode() == null || !request.scriptCode().matches("[A-Z][a-z]{3}"))
            throw new ResponseStatusException(BAD_REQUEST, "书写系统应使用Jpan、Latn等四字母代码");
        var written = written(request.written()); var normalized = key(written, true);
        duplicate(userId, request.languageCode(), request.scriptCode(), normalized, null);
        var content = content(request.content());
        var row = new PrivateEntry(); row.id = UUID.randomUUID(); row.userId = userId;
        row.languageCode = request.languageCode(); row.scriptCode = request.scriptCode(); row.written = written;
        row.normalizedWrittenKey = normalized; row.contentJson = json.writeValueAsString(content);
        row.createdAt = Instant.now(); row.updatedAt = row.createdAt;
        row = entries.saveAndFlush(row); changes.saveAndFlush(PrivateEntryChange.saved(row)); events.publishEvent(new PrivateEntryChanged(row.id, userId)); return view(row);
    }
    /** 修改保持词条身份与语言稳定，旧编辑版本不能覆盖更新。 */
    @Transactional
    public View save(UUID userId, UUID id, Edit request) {
        var row = entries.lockOwned(id, userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "私有词条不存在"));
        if (request == null || request.version() == null || request.version() != row.version)
            throw new ResponseStatusException(CONFLICT, "私有词条已修改，请重新加载后编辑");
        var written = written(request.written()); var normalized = key(written, true);
        duplicate(userId, row.languageCode, row.scriptCode, normalized, row);
        row.written = written; row.normalizedWrittenKey = normalized; row.contentJson = json.writeValueAsString(content(request.content()));
        row.updatedAt = Instant.now(); row = entries.saveAndFlush(row);
        changes.saveAndFlush(PrivateEntryChange.saved(row));
        events.publishEvent(new PrivateEntryChanged(id, userId)); return view(row);
    }
    /** 彻底删除私有词条，学习关联、覆盖、历史及音频元数据由外键级联清除。 */
    @Transactional
    public void delete(UUID userId, UUID id) {
        var row = entries.lockOwned(id, userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "私有词条不存在"));
        contributions.deleteUnpublishedPrivate(userId, id); cleanup.scheduleEntry(id); entries.delete(row); entries.flush();
    }
    /** 投稿读取先锁住本人来源，确保确认版本和固定写法来自同一次编辑。 */
    @Transactional
    public View contributionSource(UUID userId, UUID id) {
        return view(entries.lockOwned(id, userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "私有词条不存在")));
    }
    /** 学习域只取稳定身份和内容版本，语言禁用时不参加训练。 */
    @Transactional(readOnly = true)
    public DictionaryService.LearningReference reference(UUID userId, UUID id) {
        var row = owned(userId, id); var enabled = languages.list().stream().anyMatch(l -> l.code().equals(row.languageCode) && l.enabled());
        return new DictionaryService.LearningReference(id, row.languageCode, row.written, enabled ? "PRIVATE" : "UNAVAILABLE", Math.toIntExact(row.version + 1), "PRIVATE");
    }
    /** 有效内容与公开条目使用同样的数据结构，但不经过公开词典查询。 */
    @Transactional(readOnly = true)
    public DictionaryService.PublicView entryView(UUID userId, UUID id) {
        var row = owned(userId, id); var ref = reference(userId, id);
        return new DictionaryService.PublicView(id, row.languageCode, row.scriptCode, row.written, ref.status(), "PRIVATE", ref.currentRevision(), decode(row));
    }
    /** TTS只从本人读音和例句读取发音输入，缺少发音的子项保留空值供跳过。 */
    @Transactional(readOnly = true)
    public List<DictionaryService.AudioSource> audioSources(UUID userId, UUID id) {
        var row = owned(userId, id); var value = decode(row); var result = new ArrayList<DictionaryService.AudioSource>();
        value.readings().forEach(r -> result.add(new DictionaryService.AudioSource(id, r.id(), row.languageCode, "WORD", r.pronunciationText())));
        value.senses().forEach(s -> s.examples().forEach(e -> result.add(new DictionaryService.AudioSource(id, e.id(), row.languageCode, "EXAMPLE", e.pronunciationText()))));
        return result;
    }
    /** 缺失与他人身份统一404，不暴露是否存在。 */
    private PrivateEntry owned(UUID userId, UUID id) {
        return entries.findByIdAndUserId(id, userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "私有词条不存在"));
    }
    /** 初次可不提供内容；字符串空值规范为可安全缓存和训练的空文本。 */
    private DictionaryContent content(DictionaryContent value) {
        if (value == null) value = new DictionaryContent(1, List.of(), List.of(), "个人录入", "");
        dictionary.validatePersonalContent(value);
        return new DictionaryContent(1, value.readings().stream().map(r -> new DictionaryContent.Reading(r.id(), safe(r.reading()), safe(r.pronunciationText()))).toList(),
            value.senses().stream().map(s -> new DictionaryContent.Sense(s.id(), safe(s.partOfSpeech()), safe(s.gloss()), s.examples().stream()
                .map(e -> new DictionaryContent.Example(e.id(), safe(e.text()), safe(e.pronunciationText()), safe(e.translation()), e.translationLanguage(), e.translations(), e.attribution())).toList(), s.glossLanguage(), s.translations())).toList(), safe(value.sourceName()), safe(value.license()));
    }
    /** 公私词条采用同样的书写规范化，不共享唯一键。 */
    private String key(String value, boolean required) {
        var result = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
        if (result.length() > 200 || required && result.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "单词写法不能为空或超过200字");
        return result;
    }
    /** 校验显示写法，保留原字形供本人阅读。 */
    private String written(String value) { if (value == null || value.trim().isBlank() || value.length() > 200) throw new ResponseStatusException(BAD_REQUEST, "请填写不超过200字的单词写法"); return value.trim(); }
    /** 可选文本统一为空字符串，前端无需针对空值分支处理。 */
    private String safe(String value) { return value == null ? "" : value.trim(); }
    /** 将数据库保存的结构化正文还原为词条内容。 */
    private DictionaryContent decode(PrivateEntry row) { return json.readValue(row.contentJson, DictionaryContent.class); }
    /** 返回正文及编辑版本，避免暴露内部归属字段。 */
    private View view(PrivateEntry row) { return new View(row.id, row.languageCode, row.scriptCode, row.written, decode(row), row.version, row.createdAt, row.updatedAt); }
    /** 唯一性仅约束本人、语言、书写系统和规范化后的写法。 */
    private void duplicate(UUID userId, String language, String script, String key, PrivateEntry current) {
        if ((current == null || !current.normalizedWrittenKey.equals(key)) && entries.existsByUserIdAndLanguageCodeAndScriptCodeAndNormalizedWrittenKey(userId, language, script, key))
            throw new ResponseStatusException(CONFLICT, "私有词条已存在，请使用现有词条");
    }
    /** 创建身份使用明确语言和书写系统。 */
    public record Create(String languageCode, String scriptCode, String written, DictionaryContent content) {}
    /** 编辑不切换语言，仅更新写法和内容。 */
    public record Edit(Long version, String written, DictionaryContent content) {}
    /** 私人详情，版本供下一次保存使用，不表示公开发布。 */
    public record View(UUID id, String languageCode, String scriptCode, String written, DictionaryContent content, long version, Instant createdAt, Instant updatedAt) {}
}
