package com.languagelean.languages;

import java.util.List;
import java.util.Locale;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** 管理语言配置，并向词典模块提供稳定的语言存在性查询。 */
@Service
public class LanguageAdminService {
    private final LanguageRepository languages;
    private final EntityManager entities;

    public LanguageAdminService(LanguageRepository languages, EntityManager entities) {
        this.languages = languages;
        this.entities = entities;
    }

    /** 后台可看到禁用语言，用户词典只使用启用代码。 */
    @Transactional(readOnly = true)
    public List<View> list() {
        return languages.findAll(Sort.by("code")).stream().map(this::view).toList();
    }

    /** 禁用语言仍允许后台整理词条，但不允许公开发布。 */
    @Transactional(readOnly = true)
    public void requireLanguage(String code, boolean enabled) {
        var language = languages.findById(code)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "语言不存在"));
        if (enabled && !language.isEnabled()) throw new ResponseStatusException(BAD_REQUEST, "请先启用该语言");
    }

    /** 保存时校验编辑版本；新增语言默认启用客户端已有的听音回忆契约。 */
    @Transactional
    public View save(String code, Edit edit) {
        if (edit == null || code == null || !code.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*") || code.length() > 16)
            throw new ResponseStatusException(BAD_REQUEST, "语言代码格式不正确");
        if (edit.displayName() == null || edit.displayName().isBlank() || edit.displayName().length() > 80
                || edit.pronunciationLocale() == null || edit.pronunciationLocale().length() > 35)
            throw new ResponseStatusException(BAD_REQUEST, "请填写语言名称和有效的发音区域");
        try {
            if (new Locale.Builder().setLanguageTag(edit.pronunciationLocale()).build().getLanguage().isEmpty())
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException | java.util.IllformedLocaleException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "发音区域格式应类似 ja-JP");
        }
        var existing = languages.findById(code);
        var language = existing.orElseGet(() -> LanguageEntity.create(code));
        if (existing.isPresent() && (edit.version() == null || edit.version() != language.getVersion())
                || existing.isEmpty() && edit.version() != null)
            throw new ResponseStatusException(CONFLICT, "语言已被修改，请刷新后重试");
        language.update(edit.displayName().trim(), edit.pronunciationLocale(), edit.enabled());
        if (existing.isEmpty()) {
            entities.persist(language);
            entities.persist(LanguageReviewTypeEntity.listenRecall(language));
        }
        entities.flush();
        return view(language);
    }

    /** 对外返回 DTO，避免暴露 JPA 关联和实体写入能力。 */
    private View view(LanguageEntity language) {
        return new View(language.getCode(), language.getDisplayName(), language.getPronunciationLocale(),
                language.isEnabled(), language.getVersion());
    }
    /** null 版本表示创建，更新须带上读取时的版本。 */
    public record Edit(String displayName, String pronunciationLocale, boolean enabled, Long version) {}
    /** 后台语言配置视图。 */
    public record View(String code, String displayName, String pronunciationLocale, boolean enabled, long version) {}
}
