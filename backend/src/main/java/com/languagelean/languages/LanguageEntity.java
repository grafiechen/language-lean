package com.languagelean.languages;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/** 后台维护的学习语言配置实体。 */
@Entity
@Table(name = "language_config")
class LanguageEntity {
    @Id
    @Column(length = 16)
    private String code;
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;
    @Column(name = "pronunciation_locale", nullable = false, length = 35)
    private String pronunciationLocale;
    @Column(nullable = false)
    private boolean enabled;
    @Version
    private long version;
    @OneToMany(mappedBy = "language", fetch = FetchType.LAZY)
    @OrderBy("id.typeId ASC")
    private List<LanguageReviewTypeEntity> reviewTypes = new ArrayList<>();

    /** 仅供 JPA 反射创建实体。 */
    protected LanguageEntity() {}
    /** 新语言默认配置听音回忆，题型由管理服务一并创建。 */
    static LanguageEntity create(String code) {
        var language = new LanguageEntity();
        language.code = code;
        return language;
    }
    /** 更新展示和可用性，不改变稳定语言代码。 */
    void update(String name, String locale, boolean enabled) {
        this.displayName = name;
        this.pronunciationLocale = locale;
        this.enabled = enabled;
    }
    boolean isEnabled() { return enabled; }
    long getVersion() { return version; }
    String getCode() { return code; }
    String getDisplayName() { return displayName; }
    String getPronunciationLocale() { return pronunciationLocale; }
    List<LanguageReviewTypeEntity> getReviewTypes() { return reviewTypes; }
}
