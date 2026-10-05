package com.languagelean.learning;

import com.languagelean.dictionary.*;
import com.languagelean.languages.LanguageAdminService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 个人CSV预检和确认：复用本人已有身份和进度，新增内容只写个人域，不发布基准词典。 */
@Service
public class LearningCsvImportService {
    private final WordbookRepository books;
    private final PrivateEntryRepository privateRows;
    private final UserLearningItemRepository items;
    private final WordbookLearningItemRepository links;
    private final DictionaryService dictionary;
    private final PrivateEntryService privateEntries;
    private final PersonalContentService personal;
    private final LearningService learning;
    private final LanguageAdminService languages;

    LearningCsvImportService(WordbookRepository books, PrivateEntryRepository privateRows, UserLearningItemRepository items,
            WordbookLearningItemRepository links, DictionaryService dictionary, PrivateEntryService privateEntries,
            PersonalContentService personal, LearningService learning, LanguageAdminService languages) {
        this.books = books; this.privateRows = privateRows; this.items = items; this.links = links;
        this.dictionary = dictionary; this.privateEntries = privateEntries; this.personal = personal;
        this.learning = learning; this.languages = languages;
    }

    /** 预检不创建身份、进度、关联或音频任务；报告只属于当前认证账户。 */
    @Transactional(readOnly = true)
    public Report preview(UUID userId, UUID bookId, byte[] bytes, String translationLanguage) {
        ownedBook(userId, bookId, false); return process(userId, bookId, bytes, translationLanguage, false);
    }

    /** 确认重新预检文件并核对哈希，批次写入原子提交；网络重试自动识别已存在词条。 */
    @Transactional
    public Report confirm(UUID userId, UUID bookId, byte[] bytes, String translationLanguage, String expectedHash) {
        ownedBook(userId, bookId, true);
        if (!Objects.equals(hash(bytes, translationLanguage), expectedHash))
            throw new ResponseStatusException(CONFLICT, "导入文件或译文语言已变更，请重新预检");
        return process(userId, bookId, bytes, translationLanguage, true);
    }

    /** 固定列顺序的ja/Jpan第一版，规范化写法作为文件内及本人范围的去重键。 */
    private Report process(UUID userId, UUID bookId, byte[] bytes, String translationLanguage, boolean write) {
        if (translationLanguage == null || !translationLanguage.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}") || translationLanguage.length() > 35)
            throw new ResponseStatusException(BAD_REQUEST, "请提供有效的译文语言");
        languages.requireLanguage("ja", true);
        var rows = LearningCsvParser.parse(bytes); var report = new ArrayList<Line>(); var seen = new HashSet<String>();
        int added = 0, linked = 0, skipped = 0, errors = 0;
        for (var row : rows) {
            var fields = row.fields(); String written = fields.getFirst().strip();
            String key = Normalizer.normalize(written, Normalizer.Form.NFKC).strip();
            Input input;
            try { input = input(fields, translationLanguage); }
            catch (ResponseStatusException invalid) { errors++; report.add(new Line(row.line(), written, "ERROR", invalid.getReason())); continue; }
            if (!seen.add(key)) { skipped++; report.add(new Line(row.line(), written, "DUPLICATE", "文件内词条已存在，跳过")); continue; }
            var privateEntry = privateRows.findByUserIdAndLanguageCodeAndScriptCodeAndNormalizedWrittenKey(userId, "ja", "Jpan", key);
            var publicEntry = privateEntry.isPresent() ? Optional.<DictionaryService.PublicView>empty() : dictionary.exactReference("ja", "Jpan", key);
            if (publicEntry.isPresent() && !"PUBLISHED".equals(publicEntry.get().status())) {
                skipped++; report.add(new Line(row.line(), written, "UNAVAILABLE", "基准词条已封禁或不可用，跳过")); continue;
            }
            var existing = privateEntry.isPresent() ? items.findByUserIdAndPersonalCustomEntryId(userId, privateEntry.get().id)
                : publicEntry.isPresent() ? items.findByUserIdAndDictionaryEntryId(userId, publicEntry.get().id()) : Optional.<UserLearningItem>empty();
            if (existing.isPresent() && links.existsByIdWordbookIdAndIdLearningItemId(bookId, existing.get().id)) {
                skipped++; report.add(new Line(row.line(), written, "EXISTS", "词条已存在于本单词本，跳过；内容和进度保留")); continue;
            }
            boolean reuse = privateEntry.isPresent() || existing.isPresent();
            if (write) {
                LearningService.LearningItemView item;
                if (privateEntry.isPresent()) item = learning.addPrivateEntry(userId, bookId, privateEntry.get().id);
                else if (publicEntry.isPresent()) item = learning.addDictionaryEntry(userId, bookId, publicEntry.get().id());
                else {
                    var created = privateEntries.create(userId, new PrivateEntryService.Create("ja", "Jpan", written, input.content()));
                    item = learning.addPrivateEntry(userId, bookId, created.id());
                }
                // 仅为全新学习身份设置CSV释义和读音；跨本复用时不改变任何本人已有内容。
                if (!reuse && (publicEntry.isPresent() || !input.tags().isEmpty())) {
                    personal.save(userId, item.id(), new PersonalContentService.Save(0L, null, "", input.tags(), publicEntry.isPresent(),
                        publicEntry.isPresent() && !input.content().readings().isEmpty() ? input.content().readings() : null,
                        publicEntry.isPresent() ? input.content().senses() : null));
                }
            }
            if (reuse) linked++; else added++;
            report.add(new Line(row.line(), written, reuse ? "LINK" : "NEW", reuse ? "已存在个人词条或进度，仅增加本单词本关联" : publicEntry.isPresent() ? "引用基准词条，CSV内容保存为个人覆盖" : "新增个人词条并加入单词本"));
        }
        return new Report(hash(bytes, translationLanguage), write, added, linked, skipped, errors, List.copyOf(report));
    }

    /** 每行校验不修改数据库；例句正文与发音分开，空发音不会自动用正文代替。 */
    private Input input(List<String> fields, String language) {
        if (fields.size() < 3 || fields.size() > 7) throw new ResponseStatusException(BAD_REQUEST, "应为3至7列，请使用模板顺序");
        String written = value(fields, 0, 200, true), reading = value(fields, 1, 200, false), gloss = value(fields, 2, 4000, true);
        var key = Normalizer.normalize(written, Normalizer.Form.NFKC).strip();
        if (key.isEmpty() || key.length() > 200) throw new ResponseStatusException(BAD_REQUEST, "规范化单词写法不能为空或超过200字");
        var tags = Arrays.stream(value(fields, 3, 2000, false).split("\\|")).map(String::strip).filter(tag -> !tag.isEmpty()).distinct().toList();
        if (tags.size() > 20 || tags.stream().anyMatch(tag -> tag.length() > 50)) throw new ResponseStatusException(BAD_REQUEST, "标签最多20个，每个不超过50字");
        var example = value(fields, 4, 2000, false); var pronunciation = value(fields, 5, 2000, false); var translation = value(fields, 6, 2000, false);
        if (example.isEmpty() && (!pronunciation.isEmpty() || !translation.isEmpty())) throw new ResponseStatusException(BAD_REQUEST, "请填写例句正文，或清空例句发音和译文");
        var readings = reading.isEmpty() ? List.<DictionaryContent.Reading>of() : List.of(new DictionaryContent.Reading(UUID.randomUUID(), reading, reading));
        var examples = example.isEmpty() ? List.<DictionaryContent.Example>of() : List.of(new DictionaryContent.Example(UUID.randomUUID(), example, pronunciation, translation, language, Map.of(), null));
        var content = new DictionaryContent(1, readings, List.of(new DictionaryContent.Sense(UUID.randomUUID(), "", gloss, examples, language, Map.of())), "个人CSV导入", "");
        dictionary.validatePersonalContent(content);
        return new Input(content, tags);
    }
    /** 控制字符拒绝进入正文，但引号内正常换行和制表符可用于个人笔记。 */
    private String value(List<String> fields, int index, int maximum, boolean required) {
        String value = index < fields.size() ? fields.get(index).strip() : "";
        if (value.length() > maximum || required && value.isEmpty() || value.codePoints().anyMatch(c -> c == 0xFFFD || Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw new ResponseStatusException(BAD_REQUEST, "第" + (index + 1) + "列为空、过长或存在损坏字符");
        return value;
    }
    private void ownedBook(UUID userId, UUID id, boolean lock) {
        (lock ? books.lockOwned(id, userId) : books.findByIdAndUserId(id, userId))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "单词本不存在"));
    }
    /** 哈希同时固定原始字节和译文语言，不能确认另一份文件或另一种语义。 */
    private String hash(byte[] bytes, String language) {
        if (bytes == null) throw new ResponseStatusException(BAD_REQUEST, "请选择CSV文件");
        try { var digest = MessageDigest.getInstance("SHA-256"); digest.update(bytes); digest.update(("\n" + language).getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(digest.digest()); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Input(DictionaryContent content, List<String> tags) {}
    /** NEW/LINK可确认，其余状态说明未导入原因；行号来自原文件。 */
    public record Line(int line, String written, String status, String message) {}
    public record Report(String fileHash, boolean committed, int added, int linked, int skipped, int errors, List<Line> rows) {}
}
