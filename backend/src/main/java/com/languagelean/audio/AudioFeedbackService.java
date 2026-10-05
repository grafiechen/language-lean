package com.languagelean.audio;

import com.languagelean.dictionary.DictionaryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 公共发音反馈、权限隔离和人工处理；反馈本身不触发云调用或学习进度更新。 */
@Service
public class AudioFeedbackService {
    private final AudioFeedbackRepository reports;
    private final DictionaryService dictionary;
    private final AudioAssetRepository assets;
    private final AudioVersionRepository versions;
    /** 注入 JPA 仓储与公开词典查询，禁止收集草稿和个人音频。 */
    AudioFeedbackService(AudioFeedbackRepository reports, DictionaryService dictionary,
                         AudioAssetRepository assets, AudioVersionRepository versions) {
        this.reports = reports; this.dictionary = dictionary; this.assets = assets; this.versions = versions;
    }
    /** 请求 ID 绑定本人和完整提交内容；失败重试不会生成多条反馈。 */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View submit(UUID owner, Submit request) {
        if (request.id() == null || request.entryId() == null || request.resourceId() == null || request.kind() == null)
            throw bad("反馈缺少词条或发音标识");
        if (!Set.of("WRONG_PRONUNCIATION", "UNPLAYABLE").contains(Objects.toString(request.category(), ""))) throw bad("请选择有效的问题类型");
        var description = text(request.description(), "请填写问题说明");
        var pronunciation = Objects.toString(request.pronunciationText(), "").trim();
        var previous = reports.findById(request.id()).orElse(null);
        if (previous != null) {
            if (!previous.submittedBy.equals(owner) || !previous.entryId.equals(request.entryId())
                    || !previous.resourceId.equals(request.resourceId()) || !previous.kind.equals(request.kind().name())
                    || !previous.category.equals(request.category()) || !previous.description.equals(description)
                    || !previous.pronunciationText.equals(pronunciation)
                    || !Objects.equals(previous.reportedAudioVersionId, request.audioVersionId())) throw conflict("反馈标识已使用，请重新打开表单");
            return view(previous);
        }
        var source = source(request.entryId(), request.resourceId(), request.kind());
        if (pronunciation.isBlank()) throw bad("未填写发音的资源不能报告音频问题");
        if (!source.pronunciationText().trim().equals(pronunciation)) throw conflict("公开发音已变化，请刷新详情后重新反馈");
        if (request.audioVersionId() != null) {
            var clip = versions.findById(request.audioVersionId()).orElseThrow(() -> bad("公开音频版本不存在"));
            if (!clip.audioAssetId.equals(assetId(request.entryId(), request.resourceId(), request.kind()))) throw bad("音频版本不属于这个公开资源");
        }
        var report = new AudioFeedback(); report.id = request.id(); report.submittedBy = owner;
        report.entryId = request.entryId(); report.resourceId = request.resourceId(); report.kind = request.kind().name();
        report.category = request.category(); report.reportedRevision = source.currentRevision();
        report.reportedAudioVersionId = request.audioVersionId(); report.pronunciationText = pronunciation;
        var baseline = assets.findById(assetId(request.entryId(), request.resourceId(), request.kind())).orElse(null);
        report.baselineAudioVersionId = baseline == null ? null : baseline.currentVersionId;
        report.description = description; report.createdAt = Instant.now();
        return view(reports.saveAndFlush(report));
    }
    /** 只返回一页明确提交的反馈，不读取用户私人学习内容。 */
    @Transactional(readOnly = true)
    public DictionaryService.Results<View> list(UUID owner, String status, UUID entryId, UUID resourceId, String kind, int page) {
        if (page < 0 || page > 100000) throw bad("页码无效");
        if (!Set.of("", "PENDING", "RESOLVED", "DISMISSED").contains(status)) throw bad("反馈状态无效");
        if (!Set.of("", "WORD", "EXAMPLE").contains(kind)) throw bad("发音类型无效");
        var rows = reports.search(owner, status, entryId, resourceId, kind,
                PageRequest.of(page, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new DictionaryService.Results<>(rows.map(this::view).getContent(), rows.getTotalElements(), page);
    }
    /** 后台查看当前公开发音，变更或封禁时保留问题快照供人工判断。 */
    @Transactional(readOnly = true)
    public Detail detail(UUID id) { return detail(find(id)); }
    /** 新音频生成在外层事务之外进行；失败不会把反馈误标为已修复。 */
    @Transactional(readOnly = true)
    public Detail prepareGeneration(UUID id, long version) {
        var report = find(id); pending(report, version);
        var detail = detail(report);
        if (!detail.available()) throw conflict("公开资源已移除或不可用，请核对后填写处理说明");
        return detail;
    }
    /** 修复需有更新且匹配当前文本的公共音频；关闭无需修复时也必须向用户解释。 */
    @Transactional
    public View resolve(UUID actor, UUID id, Action action) {
        if (!Set.of("RESOLVED", "DISMISSED").contains(Objects.toString(action.status(), ""))) throw bad("处理状态无效");
        var note = text(action.note(), "请填写处理说明");
        var report = reports.lockById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "反馈不存在"));
        // 网络重试允许复用完全相同的最终处理，但不能改写已经完成的审核。
        if (!report.status.equals("PENDING") && report.status.equals(action.status()) && report.resolutionNote.equals(note)
                && Objects.equals(report.reviewedBy, actor) && report.version == action.version() + 1) return view(report);
        pending(report, action.version());
        if (action.status().equals("RESOLVED")) {
            var current = detail(report);
            if (!current.available() || current.currentAudioVersionId() == null
                    || Objects.equals(current.currentAudioVersionId(), report.reportedAudioVersionId)
                    || Objects.equals(current.currentAudioVersionId(), report.baselineAudioVersionId))
                throw conflict("请先修正并成功生成新的公共音频，再标记已修复");
            var clip = versions.findById(current.currentAudioVersionId()).orElseThrow();
            if (!clip.textHash.equals(hash(current.currentPronunciation()))) throw conflict("当前音频与公开发音不一致，请重新生成");
        }
        report.status = action.status(); report.resolutionNote = note; report.reviewedBy = actor; report.reviewedAt = Instant.now();
        reports.flush(); return view(report);
    }
    /** 找不到公开资源时只隐藏当前内容，不能退回草稿或私人内容。 */
    private Detail detail(AudioFeedback report) {
        var source = dictionary.availableAudioSource(report.entryId, report.resourceId, report.kind).orElse(null);
        if (source == null) return new Detail(view(report), "公开资源已移除或不可用", "", null, false, null);
        var pronunciation = source.pronunciationText().trim();
        var asset = assets.findById(assetId(report.entryId, report.resourceId, AudioService.Kind.valueOf(report.kind))).orElse(null);
        return new Detail(view(report), source.written(), pronunciation, source.currentRevision(), !pronunciation.isBlank(),
                asset == null ? null : asset.currentVersionId);
    }
    /** 稳定资源标识必须与公开音频生成算法一致，不接受客户端的权限范围。 */
    private UUID assetId(UUID entryId, UUID resourceId, AudioService.Kind kind) {
        return UUID.nameUUIDFromBytes((entryId + ":PUBLISHED:" + kind + ":" + resourceId).getBytes(StandardCharsets.UTF_8));
    }
    /** 严格按公开资源类型与 ID 查询，不根据客户端文本搜索他人内容。 */
    private DictionaryService.PublishedAudioSource source(UUID entryId, UUID resourceId, AudioService.Kind kind) {
        return dictionary.availableAudioSource(entryId, resourceId, kind.name())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "公开发音资源不存在"));
    }
    /** 审核以页面读到的版本为基准，不能覆盖其他管理员已完成的处理。 */
    private void pending(AudioFeedback report, long version) {
        if (report.version != version || !report.status.equals("PENDING")) throw conflict("反馈已被处理或修改，请重新读取");
    }
    /** 反馈存在性统一返回 404，不暴露其他人的输入。 */
    private AudioFeedback find(UUID id) { return reports.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "反馈不存在")); }
    /** 反馈和处理说明限制长度并拒绝控制字符，保留换行。 */
    private String text(String input, String required) {
        var value = Objects.toString(input, "").trim();
        if (value.isEmpty()) throw bad(required);
        if (value.length() > 1000 || value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw bad("说明最多1000字，不得包含控制字符");
        return value;
    }
    /** 与音频输入相同的 SHA-256，不含服务商凭据。 */
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.trim().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 外部视图不包含提交人的用户名、邮箱或对象存储键。 */
    private View view(AudioFeedback f) {
        return new View(f.id, f.entryId, f.resourceId, f.kind, f.category, f.reportedRevision, f.reportedAudioVersionId,
                f.pronunciationText, f.description, f.status, f.resolutionNote, f.createdAt, f.reviewedAt, f.version);
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(CONFLICT, message); }
    /** 用户请求只包含公开音频定位及自愿提交的说明。 */
    public record Submit(UUID id, UUID entryId, UUID resourceId, AudioService.Kind kind, String category, String pronunciationText, UUID audioVersionId, String description) {}
    /** 审核结果与客户端基准版本。 */
    public record Action(long version, String status, String note) {}
    /** 本人和管理员共同可见的反馈快照。 */
    public record View(UUID id, UUID entryId, UUID resourceId, String kind, String category, int reportedRevision, UUID reportedAudioVersionId,
                       String pronunciationText, String description, String status, String resolutionNote, Instant createdAt, Instant reviewedAt, long version) {}
    /** 后台公开资源现状，不加载私人条目。 */
    public record Detail(View report, String written, String currentPronunciation, Integer currentRevision, boolean available, UUID currentAudioVersionId) {}
}
