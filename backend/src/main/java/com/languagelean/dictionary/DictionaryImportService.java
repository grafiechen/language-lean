package com.languagelean.dictionary;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import com.languagelean.dictionary.DictionaryContent.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.http.HttpStatus.*;

/** 开源词典导入用例：保存预览快照，确认后才把新词条发布到基准词典。 */
@Service
public class DictionaryImportService {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_ENTRIES = 5000;
    private static final int REPORT_LIMIT = 200;
    private final DictionaryImportBatchRepository batches;
    private final DictionaryService dictionary;
    private final ObjectMapper json;

    DictionaryImportService(DictionaryImportBatchRepository batches, DictionaryService dictionary, ObjectMapper json) {
        this.batches = batches;
        this.dictionary = dictionary;
        this.json = json;
    }

    /** 以严格 UTF-8 读取文件，逐条报告无效和重复项，不在预览阶段改动公开词典。 */
    @Transactional
    public BatchView preview(MultipartFile file, UUID actor) {
        var bytes = bytes(file);
        var imported = parse(bytes);
        var source = source(imported.source());
        if (imported.schemaVersion() != 1 || imported.entries() == null)
            throw bad("导入文件 schemaVersion 必须为 1，并包含 entries 数组");
        if (imported.entries().isEmpty() || imported.entries().size() > MAX_ENTRIES)
            throw bad("一次导入应包含 1 至 " + MAX_ENTRIES + " 个词条");

        var ready = new ArrayList<DictionaryService.Create>();
        var report = new ArrayList<ReportRow>();
        var identities = new HashSet<String>();
        int duplicates = 0;
        int invalid = 0;
        for (int index = 0; index < imported.entries().size(); index++) {
            var raw = imported.entries().get(index);
            try {
                var request = dictionary.prepare(toCreate(raw, source), true);
                var identity = request.languageCode() + "\u0000" + request.scriptCode() + "\u0000"
                        + dictionary.normalizedKey(request.written());
                if (!identities.add(identity)) {
                    duplicates++;
                    report.add(row(index, raw, "DUPLICATE", "文件内重复，已跳过"));
                } else if (dictionary.exists(request)) {
                    duplicates++;
                    report.add(row(index, raw, "DUPLICATE", "基准词典中已存在，已跳过且不会覆盖"));
                } else {
                    ready.add(request);
                    report.add(row(index, raw, "READY", "校验通过，确认后发布"));
                }
            } catch (RuntimeException ex) {
                invalid++;
                report.add(row(index, raw, "INVALID", reason(ex)));
            }
        }
        var payload = json.writeValueAsString(new PreparedPayload(ready));
        var reportJson = json.writeValueAsString(new ReportPayload(report));
        var batch = DictionaryImportBatch.validated(fileName(file), sha256(bytes), source,
                imported.entries().size(), ready.size(), duplicates, invalid, payload, reportJson, actor);
        batches.saveAndFlush(batch);
        return view(batch);
    }

    /** 确认时锁定批次并再次查重；新出现的重复会被明确计数，绝不覆盖原词条。 */
    @Transactional
    public BatchView apply(UUID id, Apply request, UUID actor) {
        var batch = batches.lockById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "导入批次不存在"));
        if (request == null || request.version() == null || request.version() != batch.version)
            throw new ResponseStatusException(CONFLICT, "导入预览已变化，请刷新后重试");
        if (!batch.status.equals("VALIDATED")) throw bad("这个导入批次已经确认过");
        var payload = json.readValue(batch.normalizedPayload, PreparedPayload.class);
        int imported = 0;
        int duplicateAtApply = 0;
        var note = "批量导入：" + batch.sourceName + " " + batch.sourceVersion;
        for (var entry : payload.entries()) {
            if (dictionary.importPublished(entry, actor, note)) imported++;
            else duplicateAtApply++;
        }
        batch.applied(imported, duplicateAtApply);
        batches.flush();
        return view(batch);
    }

    /** 返回最近 20 个批次，管理员可以重新打开未确认的预览。 */
    @Transactional(readOnly = true)
    public List<BatchSummary> list() {
        return batches.findTop20ByOrderByCreatedAtDesc().stream().map(this::summary).toList();
    }

    /** 批次详情包含最多 200 条逐项提示，超出部分仍保留在数据库审计快照中。 */
    @Transactional(readOnly = true)
    public BatchView detail(UUID id) {
        return view(batches.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "导入批次不存在")));
    }

    /** 外部格式不携带内部 UUID；进入规范模型时为读音、词义和例句生成稳定身份。 */
    private DictionaryService.Create toCreate(ImportEntry raw, Source source) {
        if (raw == null) throw bad("词条不能为空");
        var readings = list(raw.readings()).stream().map(value -> {
            if (value == null) throw bad("读音不能为空");
            return new Reading(UUID.randomUUID(), value.reading(), value.pronunciationText());
        }).toList();
        var senses = list(raw.senses()).stream().map(value -> {
            if (value == null) throw bad("词义不能为空");
            var examples = list(value.examples()).stream().map(example -> {
                if (example == null) throw bad("例句不能为空");
                return new Example(UUID.randomUUID(), example.text(), example.pronunciationText(), example.translation(), example.translationLanguage(), example.translations(), example.attribution());
            }).toList();
            return new Sense(UUID.randomUUID(), value.partOfSpeech(), value.gloss(), examples, value.glossLanguage(), value.translations());
        }).toList();
        var sourceLabel = source.name() + " " + source.version();
        var content = new DictionaryContent(1, readings, senses, sourceLabel, source.license());
        return new DictionaryService.Create(raw.languageCode(), raw.scriptCode(), raw.written(), content);
    }

    private Source source(Source raw) {
        if (raw == null) throw bad("请填写 source 来源信息");
        var name = required(raw.name(), 120, "来源名称");
        var version = required(raw.version(), 60, "来源版本");
        var license = required(raw.license(), 200, "许可证");
        if ((name + " " + version).length() > 200) throw bad("来源名称和版本合计不能超过 200 字");
        return new Source(name, version, license);
    }

    private String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max) throw bad(label + "不能为空或超过长度限制");
        return value.trim();
    }

    private byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("请选择 JSON 导入文件");
        try {
            var bytes = file.getBytes();
            if (bytes.length > MAX_BYTES) throw bad("导入文件不能超过 10 MB");
            return bytes;
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw bad("无法读取导入文件"); }
    }

    private ImportFile parse(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return json.readValue(text, ImportFile.class);
        } catch (CharacterCodingException ex) { throw bad("文件必须使用 UTF-8 编码"); }
        catch (RuntimeException ex) { throw bad("JSON 格式无法解析：" + safe(ex.getMessage())); }
    }

    private BatchView view(DictionaryImportBatch batch) {
        var rows = json.readValue(batch.validationReport, ReportPayload.class).rows();
        var visible = rows.size() > REPORT_LIMIT ? rows.subList(0, REPORT_LIMIT) : rows;
        return new BatchView(batch.id, batch.status, batch.originalFileName, batch.originalSha256,
                new Source(batch.sourceName, batch.sourceVersion, batch.licenseText), batch.totalCount,
                batch.readyCount, batch.duplicateCount, batch.invalidCount, batch.importedCount,
                batch.applyDuplicateCount, batch.createdAt, batch.appliedAt, batch.version,
                List.copyOf(visible), rows.size() > REPORT_LIMIT);
    }

    private BatchSummary summary(DictionaryImportBatch batch) {
        return new BatchSummary(batch.id, batch.status, batch.originalFileName, batch.sourceName,
                batch.sourceVersion, batch.totalCount, batch.readyCount, batch.duplicateCount,
                batch.invalidCount, batch.importedCount, batch.applyDuplicateCount, batch.createdAt,
                batch.appliedAt, batch.version);
    }

    private ReportRow row(int index, ImportEntry raw, String status, String message) {
        return new ReportRow(index + 1, raw == null ? "" : safe(raw.written()),
                raw == null ? "" : safe(raw.languageCode()), status, message);
    }
    private String reason(RuntimeException ex) {
        if (ex instanceof ResponseStatusException response && response.getReason() != null) return response.getReason();
        return safe(ex.getMessage()).isBlank() ? "内容格式不正确" : safe(ex.getMessage());
    }
    private String fileName(MultipartFile file) {
        var value = safe(file.getOriginalFilename()).replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1);
        if (value.isBlank()) value = "dictionary-import.json";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }
    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private String safe(String value) { return value == null ? "" : value; }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }

    /** 与具体开源词典适配器解耦的规范化上传文件。 */
    public record ImportFile(int schemaVersion, Source source, List<ImportEntry> entries) {}
    /** 来源、版本和许可证必须在确认导入前明确。 */
    public record Source(String name, String version, String license) {}
    public record ImportEntry(String languageCode, String scriptCode, String written,
                              List<ImportReading> readings, List<ImportSense> senses) {}
    public record ImportReading(String reading, String pronunciationText) {}
    public record ImportSense(String partOfSpeech, String gloss, List<ImportExample> examples, String glossLanguage, java.util.Map<String, DictionaryContent.Translation> translations) {
        public ImportSense(String partOfSpeech, String gloss, List<ImportExample> examples) { this(partOfSpeech, gloss, examples, "", java.util.Map.of()); }
    }
    public record ImportExample(String text, String pronunciationText, String translation, String translationLanguage, java.util.Map<String, DictionaryContent.Translation> translations, DictionaryContent.Attribution attribution) {
        public ImportExample(String text, String pronunciationText, String translation) { this(text, pronunciationText, translation, "", java.util.Map.of(), null); }
    }
    /** 确认请求携带预览版本，阻止旧页面重复提交。 */
    public record Apply(Long version) {}
    /** 每条提示明确区分可导入、已存在和无效。 */
    public record ReportRow(int row, String written, String languageCode, String status, String message) {}
    private record PreparedPayload(List<DictionaryService.Create> entries) {}
    private record ReportPayload(List<ReportRow> rows) {}
    public record BatchSummary(UUID id, String status, String fileName, String sourceName, String sourceVersion,
                               int totalCount, int readyCount, int duplicateCount, int invalidCount,
                               int importedCount, int applyDuplicateCount, Instant createdAt, Instant appliedAt,
                               long version) {}
    public record BatchView(UUID id, String status, String fileName, String sha256, Source source,
                            int totalCount, int readyCount, int duplicateCount, int invalidCount,
                            int importedCount, int applyDuplicateCount, Instant createdAt, Instant appliedAt,
                            long version, List<ReportRow> rows, boolean rowsTruncated) {}
}
