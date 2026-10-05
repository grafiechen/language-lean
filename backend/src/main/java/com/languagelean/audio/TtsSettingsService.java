package com.languagelean.audio;

import com.languagelean.languages.LanguageAdminService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 管理非敏感 TTS 配置，并为生成服务提供不可变参数快照。 */
@Service
public class TtsSettingsService {
    private final TtsLanguageSettingRepository settings;
    private final LanguageAdminService languages;
    /** 注入语言边界和设置仓储。 */
    TtsSettingsService(TtsLanguageSettingRepository settings, LanguageAdminService languages) {
        this.settings = settings; this.languages = languages;
    }
    /** 未配置的新语言默认不生成，个人自动生成默认开启供后续个人模块复用。 */
    @Transactional(readOnly = true)
    public List<View> list() {
        return languages.list().stream().map(language -> {
            var setting = settings.findById(language.code());
            return setting.map(s -> view(s, language.pronunciationLocale(), language.displayName()))
                    .orElse(new View(language.code(), language.displayName(), language.pronunciationLocale(),
                            "GOOGLE", "Chirp3-HD", "", false, true, null));
        }).toList();
    }
    /** 校验语音名称和并发版本；只允许首版已经接入的服务商和模型。 */
    @Transactional
    public View save(String code, Edit request) {
        var language = languages.list().stream().filter(l -> l.code().equals(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "语言不存在"));
        if (request == null || !"GOOGLE".equals(request.provider()) || !"Chirp3-HD".equals(request.model())
                || request.voice() == null || request.voice().length() > 120
                || !request.voice().matches(java.util.regex.Pattern.quote(language.pronunciationLocale()) + "-Chirp3-HD-[A-Za-z]+"))
            throw new ResponseStatusException(BAD_REQUEST, "声音名称应匹配当前语言的 Chirp3-HD 声音");
        var existing = settings.findById(code);
        if (existing.isPresent() && (request.version() == null || request.version() != existing.get().version)
                || existing.isEmpty() && request.version() != null)
            throw new ResponseStatusException(CONFLICT, "TTS 配置已修改，请刷新重试");
        var setting = existing.orElseGet(TtsLanguageSetting::new);
        setting.languageCode = code; setting.voice = request.voice();
        setting.enabled = request.enabled(); setting.personalAutoGenerate = request.personalAutoGenerate();
        setting = settings.saveAndFlush(setting);
        return view(setting, language.pronunciationLocale(), language.displayName());
    }
    /** 参数快照包含发音区域；语言禁用时也禁止生成。 */
    @Transactional(readOnly = true)
    public Profile profile(String code) {
        var language = languages.list().stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow();
        var setting = settings.findById(code);
        return setting.map(s -> new Profile(s.provider, s.model, s.voice, language.pronunciationLocale(),
                s.enabled && language.enabled(), s.personalAutoGenerate))
                .orElse(new Profile("GOOGLE", "Chirp3-HD", "", language.pronunciationLocale(), false, true));
    }
    /** 转换界面字段，版本不与复习时间戳混用。 */
    private View view(TtsLanguageSetting s, String locale, String name) {
        return new View(s.languageCode, name, locale, s.provider, s.model, s.voice, s.enabled, s.personalAutoGenerate, s.version);
    }
    /** 非敏感后台编辑请求。 */
    public record Edit(String provider, String model, String voice, boolean enabled, boolean personalAutoGenerate, Long version) {}
    /** 后台配置视图。 */
    public record View(String languageCode, String displayName, String locale, String provider, String model,
                       String voice, boolean enabled, boolean personalAutoGenerate, Long version) {}
    /** 单次生成使用的稳定参数。 */
    public record Profile(String provider, String model, String voice, String locale, boolean enabled, boolean personalAutoGenerate) {}
}
