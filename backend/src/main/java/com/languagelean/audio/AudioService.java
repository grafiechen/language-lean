package com.languagelean.audio;

import com.languagelean.dictionary.DictionaryService;
import com.languagelean.learning.PrivateEntryService;
import com.languagelean.learning.PersonalContentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 权限校验、短事务认领、云生成和上传成功后的原子切换。 */
@Service
public class AudioService implements AudioGenerationPort {
    private final AudioTransactions transactions;
    private final AudioAssetRepository assets;
    private final AudioVersionRepository versions;
    private final DictionaryService dictionary;
    private final TtsSettingsService settings;
    private final SpeechSynthesizer speech;
    private final AudioObjectStore store;
    private final PrivateEntryService privateEntries;
    private final AudioCleanupService cleanup;
    private final PersonalContentService personal;
    private final java.util.concurrent.Semaphore generationCapacity = new java.util.concurrent.Semaphore(4);
    /** 注入可替换基础设施；不在业务类中保存或返回凭据。 */
    AudioService(AudioTransactions transactions, AudioAssetRepository assets, AudioVersionRepository versions,
                 DictionaryService dictionary, TtsSettingsService settings, SpeechSynthesizer speech, AudioObjectStore store,
                 PrivateEntryService privateEntries, AudioCleanupService cleanup, PersonalContentService personal) {
        this.transactions = transactions; this.assets = assets; this.versions = versions;
        this.dictionary = dictionary; this.settings = settings; this.speech = speech; this.store = store;
        this.privateEntries = privateEntries; this.cleanup = cleanup;
        this.personal = personal;
    }
    /** 个人范围必须附当前认证账户，管理员也不能绕过归属。 */
    public enum Scope { PUBLISHED, DRAFT, PERSONAL, OVERRIDE }
    public enum Kind { WORD, EXAMPLE }
    /** 可直接供后台展示的配置完整状态，不包含任何密钥。 */
    public Availability availability() { return new Availability(speech.configured(), store.configured()); }
    public record Availability(boolean googleEnabled, boolean storageConfigured) {}

    /** 应用端口按音频身份补生成公开资源，草稿必须走明确的管理员边界。 */
    @Override public Result ensureGenerated(UUID userId, UUID assetId) {
        var asset = assets.findById(assetId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "音频资源不存在"));
        return ensureResource(sourceId(asset),
            Scope.valueOf(asset.contentScope), Kind.valueOf(asset.kind), asset.resourceId, userId, false, false);
    }
    /** 保存、详情和点击播放使用同一补生成入口；admin 只由服务端认证角色决定。 */
    public Result ensureResource(UUID entryId, Scope scope, Kind kind, UUID resourceId, boolean admin, boolean force) {
        return ensureResource(entryId, scope, kind, resourceId, null, admin, force);
    }
    /** 本人个人音频与公共音频共用生成锁，但权限、身份和对象键始终分开。 */
    public Result ensureResource(UUID entryId, Scope scope, Kind kind, UUID resourceId, UUID userId, boolean admin, boolean force) {
        authorize(scope, admin);
        var source = source(entryId, scope, kind, resourceId, userId);
        var text = source.pronunciationText() == null ? "" : source.pronunciationText().trim();
        if (text.isBlank()) return result(Status.MISSING_PRONUNCIATION, null, false, "尚未填写发音，请先补充发音文本。");
        var profile = settings.profile(source.languageCode());
        var id = UUID.nameUUIDFromBytes((entryId + ":" + scope + ":" + kind + ":" + resourceId).getBytes(StandardCharsets.UTF_8));
        AudioAsset asset = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { asset = transactions.register(id, entryId, scope.name(), kind.name(), resourceId); break; }
            catch (DataIntegrityViolationException conflict) { if (attempt == 2) throw conflict; }
        }
        var fingerprint = fingerprint(id, profile, text);
        var current = asset.currentVersionId == null ? null : versions.findById(asset.currentVersionId).orElseThrow();
        if (!force && current != null && current.generationFingerprint.equals(fingerprint) && store.configured()) {
            try { if (fileAvailable(current)) return result(Status.READY, current, false, "音频已就绪。"); }
            catch (RuntimeException unavailable) { return result(Status.FAILED, current, true, "无法检查音频存储，请稍后重试。"); }
        }
        if (!profile.enabled()) return result(Status.DISABLED, current, true, "该语言的音频生成已关闭。");
        if (personalScope(scope) && !profile.personalAutoGenerate()) return result(Status.DISABLED, current, true, "个人音频生成已由管理员关闭，已有正确音频仍可播放。");
        if (!speech.configured() || !store.configured()) return result(Status.NOT_CONFIGURED, current, true, "服务端尚未配置 Google TTS 或 R2，请联系管理员。");
        var claim = transactions.claim(id, fingerprint, force, null);
        try {
            if (claim.status().equals("READY")) {
                if (fileAvailable(claim.current())) return result(Status.READY, claim.current(), false, "音频已就绪。");
                claim = transactions.claim(id, fingerprint, false, claim.current().id);
                if (claim.status().equals("READY")) return result(Status.READY, claim.current(), false, "音频已就绪。");
            }
        } catch (RuntimeException unavailable) {
            return result(Status.FAILED, claim.current(), true, "无法检查音频存储，请稍后重试。");
        }
        if (claim.status().equals("PENDING")) return result(Status.PENDING, claim.current(), true, "音频正在生成，请稍候。");
        if (!generationCapacity.tryAcquire()) {
            transactions.fail(id, claim.token(), "CAPACITY_BUSY");
            return result(Status.PENDING, claim.current(), true, "音频任务排队中，请稍候。");
        }
        String uploadedKey = null;
        boolean committed = false;
        try {
            var bytes = speech.synthesize(profile, text);
            if (bytes.length == 0 || bytes.length > 5_000_000) throw new IllegalStateException();
            var versionId = UUID.randomUUID();
            var key = "audio/" + scope.name().toLowerCase(Locale.ROOT) + "/" + id + "/" + versionId + ".mp3";
            store.put(key, bytes);
            uploadedKey = key;
            var latest = source(entryId, scope, kind, resourceId, userId);
            var latestText = latest.pronunciationText() == null ? "" : latest.pronunciationText().trim();
            var latestProfile = settings.profile(latest.languageCode());
            if (personalScope(scope) && (!latestProfile.enabled() || !latestProfile.personalAutoGenerate())
                    || !fingerprint.equals(fingerprint(id, latestProfile, latestText))) {
                transactions.fail(id, claim.token(), "SOURCE_CHANGED");
                return result(Status.PENDING, claim.current(), true, "发音或配置已变化，请重新生成当前内容。");
            }
            var completed = transactions.complete(id, claim.token(), fingerprint, profile, hash(text.getBytes(StandardCharsets.UTF_8)), hash(bytes), versionId, key);
            committed = completed != null;
            return completed == null ? result(Status.PENDING, claim.current(), true, "已有新的生成任务，请稍候。")
                    : result(Status.READY, completed, false, "音频已就绪。");
        } catch (RuntimeException failed) {
            transactions.fail(id, claim.token(), "GENERATION_FAILED");
            return result(Status.FAILED, claim.current(), true, "音频生成或上传失败，请重试；旧版本仍保留。");
        } finally {
            generationCapacity.release();
            if (personalScope(scope) && uploadedKey != null && !committed) cleanup.orphan(uploadedKey);
        }
    }
    /** 每次取文件都重新检查公开/草稿权限和词条状态，草稿不能由普通用户播放。 */
    public byte[] content(UUID versionId, boolean admin) {
        return content(versionId, null, admin);
    }
    /** 私人文件每次读取重新校验本人，不以管理员角色或知道版本UUID授予权限。 */
    public byte[] content(UUID versionId, UUID userId, boolean admin) {
        var version = versions.findById(versionId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "音频版本不存在"));
        var asset = assets.findById(version.audioAssetId).orElseThrow();
        var scope = Scope.valueOf(asset.contentScope); authorize(scope, admin);
        source(sourceId(asset),
            scope, Kind.valueOf(asset.kind), asset.resourceId, userId);
        if (!store.configured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "音频存储尚未配置");
        try {
            var bytes = store.get(version.objectKey);
            if (bytes.length > 5_000_000 || !hash(bytes).equals(version.fileHash))
                throw new ResponseStatusException(BAD_GATEWAY, "音频文件已损坏，请重新生成");
            return bytes;
        } catch (ResponseStatusException known) { throw known; }
        catch (RuntimeException missing) { throw new ResponseStatusException(BAD_GATEWAY, "音频读取失败，请联网重试补生成"); }
    }
    /** 自动保存只在部署已配置云服务时执行，避免反复创建无效任务。 */
    public void generateSaved(UUID entryId, boolean draft) {
        if (!speech.configured() || !store.configured()) return;
        var scope = draft ? Scope.DRAFT : Scope.PUBLISHED;
        for (var source : dictionary.audioSources(entryId, draft)) {
            if (source.pronunciationText() != null && !source.pronunciationText().isBlank())
                ensureResource(entryId, scope, Kind.valueOf(source.kind()), source.resourceId(), true, false);
        }
    }
    /** 私有保存也在提交后生成，关闭个人开关时不绕过；无发音子项跳过。 */
    public void generatePersonalSaved(UUID entryId, UUID userId) {
        if (!speech.configured() || !store.configured()) return;
        for (var source : privateEntries.audioSources(userId, entryId)) {
            if (settings.profile(source.languageCode()).personalAutoGenerate() && source.pronunciationText() != null && !source.pronunciationText().isBlank())
                ensureResource(entryId, Scope.PERSONAL, Kind.valueOf(source.kind()), source.resourceId(), userId, false, false);
        }
    }
    /** 覆盖内容提交后仅生成本人覆盖的资源，不重复生成继承的公共发音。 */
    public void generateOverrideSaved(UUID itemId, UUID userId) {
        if (!speech.configured() || !store.configured()) return;
        for (var source : personal.audioSources(userId, itemId))
            if (settings.profile(source.languageCode()).personalAutoGenerate() && source.pronunciationText() != null && !source.pronunciationText().isBlank())
                ensureResource(itemId, Scope.OVERRIDE, Kind.valueOf(source.kind()), source.resourceId(), userId, false, false);
    }
    /** 私人覆盖与私人词条都受个人生成开关约束。 */
    private boolean personalScope(Scope scope) { return scope == Scope.PERSONAL || scope == Scope.OVERRIDE; }
    /** 音频来源恰好引用公开身份、私有词条或学习身份之一。 */
    private UUID sourceId(AudioAsset asset) { return asset.learningItemId != null ? asset.learningItemId : asset.personalCustomEntryId != null ? asset.personalCustomEntryId : asset.dictionaryEntryId; }
    /** 精确匹配稳定子项 ID 与类型，个人来源附账户条件。 */
    private DictionaryService.AudioSource source(UUID entryId, Scope scope, Kind kind, UUID id, UUID userId) {
        return (scope == Scope.OVERRIDE ? personal.audioSources(userId, entryId)
            : scope == Scope.PERSONAL ? privateEntries.audioSources(userId, entryId) : dictionary.audioSources(entryId, scope == Scope.DRAFT)).stream()
                .filter(s -> s.resourceId().equals(id) && s.kind().equals(kind.name())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "发音资源不存在"));
    }
    /** 草稿访问只认可服务端管理员身份。 */
    private void authorize(Scope scope, boolean admin) {
        if (scope == Scope.DRAFT && !admin) throw new ResponseStatusException(FORBIDDEN, "无权访问草稿音频");
    }
    /** 指纹包含资源权限范围、发音文本和全部声音参数，不混用公开与草稿资产。 */
    private String fingerprint(UUID id, TtsSettingsService.Profile p, String text) {
        return hash((id + "\0" + p.provider() + "\0" + p.model() + "\0" + p.voice() + "\0" + p.locale() + "\0" + text).getBytes(StandardCharsets.UTF_8));
    }
    /** 所有文件和输入统一使用 SHA-256。 */
    private String hash(byte[] input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 检查存在性和文件哈希；损坏文件与缺失文件都走锁内复查补生成。 */
    private boolean fileAvailable(AudioVersion version) {
        if (!store.exists(version.objectKey)) return false;
        var bytes = store.get(version.objectKey);
        return bytes.length <= 5_000_000 && hash(bytes).equals(version.fileHash);
    }
    /** 客户端只拿到经过权限校验的版本 URL，不拿对象存储凭据。 */
    private Result result(Status status, AudioVersion version, boolean stale, String message) {
        return new Result(status, version == null ? null : version.id, version == null ? null : "/api/v1/audio/versions/" + version.id,
                version != null && stale, message, version == null ? null : version.textHash);
    }
}
