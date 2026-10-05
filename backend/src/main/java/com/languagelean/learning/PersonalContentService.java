package com.languagelean.learning;

import com.languagelean.dictionary.DictionaryContent;
import com.languagelean.dictionary.DictionaryService;
import com.languagelean.audio.AudioCleanupService;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

/** 私有释义、笔记与标签的事务边界，不向公开词典或公共音频写入私人内容。 */
@Service
public class PersonalContentService {
    private final UserLearningItemRepository items;
    private final PersonalEntryOverrideRepository overrides;
    private final PersonalEntryOverrideChangeRepository changes;
    private final DictionaryService dictionary;
    private final ObjectMapper json;
    private final PrivateEntryService privateEntries;
    private final AudioCleanupService cleanup;
    private final ApplicationEventPublisher events;

    PersonalContentService(UserLearningItemRepository items, PersonalEntryOverrideRepository overrides,
            PersonalEntryOverrideChangeRepository changes, DictionaryService dictionary, ObjectMapper json, PrivateEntryService privateEntries,
            AudioCleanupService cleanup, ApplicationEventPublisher events) {
        this.items = items; this.overrides = overrides; this.changes = changes; this.dictionary = dictionary; this.json = json;
        this.privateEntries = privateEntries;
        this.cleanup = cleanup; this.events = events;
    }

    /** 返回当前用户有效学习内容；封禁词条仍明确返回空内容，不用个人释义绕过封禁。 */
    @Transactional(readOnly = true)
    public View detail(UUID userId, UUID itemId) {
        var item = items.findByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目不存在"));
        return view(item);
    }
    /** 提交修订时锁住本人学习身份，个人内容版本与公开基准一起核对。 */
    @Transactional
    public View contributionSource(UUID userId, UUID itemId) {
        return view(items.lockByIdAndUserId(itemId, userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目不存在")));
    }

    /** 学习列表和离线核对只取内容版本，不传出私有正文。 */
    @Transactional(readOnly = true)
    public long revision(UUID itemId) {
        return overrides.findById(itemId).map(row -> row.contentRevision).orElse(0L);
    }

    /** 发音资源版本单独返回，笔记编辑不能取消正在进行的听音训练。 */
    @Transactional(readOnly = true)
    public long audioRevision(UUID itemId) { return overrides.findById(itemId).map(row -> row.audioRevision).orElse(0L); }

    /** 同词的内容编辑串行化，并检查表单版本；保存不更改任何学习进度或重置代际。 */
    @Transactional
    public View save(UUID userId, UUID itemId, Save request) {
        var item = items.lockByIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目不存在"));
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0)
            throw new ResponseStatusException(BAD_REQUEST, "请提供个人内容的基准版本");
        var row = overrides.findById(itemId).orElseGet(() -> PersonalEntryOverride.create(itemId));
        if (row.contentRevision != request.expectedRevision())
            throw new ResponseStatusException(CONFLICT, "个人内容已在其他页面或设备修改，请重新加载后编辑");
        var meaning = request.meaningOverride() == null ? null : text(request.meaningOverride(), 4000, "个人释义");
        if (meaning != null && meaning.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "个人释义不能为空，沿用公开内容请关闭个人释义");
        var notes = text(request.notes(), 10000, "笔记");
        var tags = tags(request.tags());
        var readings = row.readingsOverrideJson; var senses = row.sensesOverrideJson;
        if (Boolean.TRUE.equals(request.replaceStructuredContent())) {
            if (item.personalCustomEntryId != null && (request.readingsOverride() != null || request.sensesOverride() != null))
                throw new ResponseStatusException(BAD_REQUEST, "独立私有词条请直接编辑词条读音和例句");
            var normalized = structured(request.readingsOverride(), request.sensesOverride());
            readings = request.readingsOverride() == null ? null : json.writeValueAsString(normalized.readings());
            senses = request.sensesOverride() == null ? null : json.writeValueAsString(normalized.senses());
        }
        if (meaning != null && senses != null) throw new ResponseStatusException(BAD_REQUEST, "个人词义及例句与简单个人释义不能同时启用");
        var audioChanged = !Objects.equals(readings, row.readingsOverrideJson) || !Objects.equals(senses, row.sensesOverrideJson);
        if (row.readingsOverrideJson != null && readings == null) cleanup.removeLearningAudio(itemId, "WORD");
        if (row.sensesOverrideJson != null && senses == null) cleanup.removeLearningAudio(itemId, "EXAMPLE");
        row.readingsOverrideJson = readings; row.sensesOverrideJson = senses;
        if (audioChanged) row.audioRevision++;
        row.meaningOverride = meaning; row.notes = notes; row.tagsJson = json.writeValueAsString(tags);
        row.contentRevision++; row.updatedAt = Instant.now();
        overrides.saveAndFlush(row); changes.saveAndFlush(PersonalEntryOverrideChange.saved(row));
        if (audioChanged) events.publishEvent(new PersonalAudioChanged(itemId, userId));
        return view(item);
    }

    /** 只读取本人实际覆盖的资源，公共继承发音仍走公共音频；封禁不能被个人覆盖绕过。 */
    @Transactional(readOnly = true)
    public List<DictionaryService.AudioSource> audioSources(UUID userId, UUID itemId) {
        var value = detail(userId, itemId);
        if (!value.entry().status().equals("PUBLISHED") || value.entry().content() == null)
            throw new ResponseStatusException(NOT_FOUND, "词条已封禁或不可用");
        var result = new ArrayList<DictionaryService.AudioSource>(); var personal = value.personal();
        if (personal.readingsOverride() != null) personal.readingsOverride().forEach(r -> result.add(
            new DictionaryService.AudioSource(itemId, r.id(), value.entry().languageCode(), "WORD", r.pronunciationText())));
        if (personal.sensesOverride() != null) personal.sensesOverride().forEach(s -> s.examples().forEach(e -> result.add(
            new DictionaryService.AudioSource(itemId, e.id(), value.entry().languageCode(), "EXAMPLE", e.pronunciationText()))));
        return result;
    }

    /** 沿用词典子项限制和UUID校验，可选字符串规范成空文本。 */
    private DictionaryContent structured(List<DictionaryContent.Reading> readings, List<DictionaryContent.Sense> senses) {
        var value = new DictionaryContent(1, readings == null ? List.of() : readings, senses == null ? List.of() : senses, "", "");
        dictionary.validatePersonalContent(value);
        return new DictionaryContent(1, value.readings().stream().map(r -> new DictionaryContent.Reading(r.id(), text(r.reading(), 200, "读音"), text(r.pronunciationText(), 500, "发音"))).toList(),
            value.senses().stream().map(s -> new DictionaryContent.Sense(s.id(), text(s.partOfSpeech(), 100, "词性"), text(s.gloss(), 4000, "释义"), s.examples().stream().map(e ->
                new DictionaryContent.Example(e.id(), text(e.text(), 2000, "例句"), text(e.pronunciationText(), 2000, "例句发音"), text(e.translation(), 2000, "译文"), e.translationLanguage(), e.translations(), e.attribution())).toList(), s.glossLanguage(), s.translations())).toList(), "", "");
    }

    /** 未覆盖的读音、词义及例句始终取最新公开版；个人释义替换整组词义，明确标注来源。 */
    private View view(UserLearningItem item) {
        var entry = item.personalCustomEntryId == null ? dictionary.detail(item.dictionaryEntryId)
            : privateEntries.entryView(item.userId, item.personalCustomEntryId);
        var personal = personalSnapshot(item.id);
        var base = entry.content();
        var effective = base;
        if (base != null) effective = new DictionaryContent(base.schemaVersion(),
            personal.readingsOverride() == null ? base.readings() : personal.readingsOverride(),
            personal.sensesOverride() == null ? base.senses() : personal.sensesOverride(), base.sourceName(), base.license());
        if (base != null && personal.meaningOverride() != null) {
            effective = new DictionaryContent(base.schemaVersion(), effective.readings(),
                    List.of(new DictionaryContent.Sense(item.id, "个人释义", personal.meaningOverride(), List.of())),
                    base.sourceName(), base.license());
        }
        var result = new DictionaryService.PublicView(entry.id(), entry.languageCode(), entry.scriptCode(), entry.written(),
                entry.status(), entry.originType(), entry.currentRevision(), effective);
        return new View(item.id, result, personal);
    }
    /** 导出本人笔记不依赖公开词条是否封禁或语言是否启用。 */
    @Transactional(readOnly = true)
    public Personal exportOwned(UUID userId, UUID itemId) {
        items.findByIdAndUserId(itemId, userId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "学习条目不存在"));
        return personalSnapshot(itemId);
    }
    /** 详情和备份复用私人字段快照，不夹带公开草稿。 */
    private Personal personalSnapshot(UUID itemId) {
        var row = overrides.findById(itemId).orElse(null);
        return row == null ? new Personal(0, null, "", List.of(), null, null, null, 0)
                : new Personal(row.contentRevision, row.meaningOverride, row.notes,
                    List.of(json.readValue(row.tagsJson, String[].class)), row.updatedAt,
                    row.readingsOverrideJson == null ? null : List.of(json.readValue(row.readingsOverrideJson, DictionaryContent.Reading[].class)),
                    row.sensesOverrideJson == null ? null : List.of(json.readValue(row.sensesOverrideJson, DictionaryContent.Sense[].class)), row.audioRevision);
    }

    /** 标签以数组提交；去空、保留顺序去重，数量和长度均有限制。 */
    private List<String> tags(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new ResponseStatusException(BAD_REQUEST, "标签最多20个");
        var result = new LinkedHashSet<String>();
        for (var value : values) {
            if (value == null) throw new ResponseStatusException(BAD_REQUEST, "标签不能为 null");
            var tag = text(value, 50, "标签"); if (!tag.isBlank()) result.add(tag);
        }
        return List.copyOf(result);
    }

    /** 私人文本只限制长度，页面使用文本插值，不把笔记当作 HTML 执行。 */
    private String text(String value, int limit, String name) {
        var result = value == null ? "" : value.trim();
        if (result.length() > limit) throw new ResponseStatusException(BAD_REQUEST, name + "超过长度限制");
        return result;
    }

    /** 空释义字段表示继承公开内容；空笔记和空标签用于清除对应个人内容。 */
    public record Save(Long expectedRevision, String meaningOverride, String notes, List<String> tags,
            Boolean replaceStructuredContent, List<DictionaryContent.Reading> readingsOverride, List<DictionaryContent.Sense> sensesOverride) {
        /** 兼容旧表单：未明确替换结构时保留已有个人读音和例句。 */
        public Save(Long expectedRevision, String meaningOverride, String notes, List<String> tags) {
            this(expectedRevision, meaningOverride, notes, tags, false, null, null);
        }
    }
    /** 只对本人返回的个人元信息；版本零表示从未保存，清空仍保留递增版本。 */
    public record Personal(long revision, String meaningOverride, String notes, List<String> tags, Instant updatedAt,
            List<DictionaryContent.Reading> readingsOverride, List<DictionaryContent.Sense> sensesOverride, long audioRevision) {}
    /** 有效内容与个人元信息组成一个快照，训练、详情和离线准备使用同一契约。 */
    public record View(UUID learningItemId, DictionaryService.PublicView entry, Personal personal) {}
}
