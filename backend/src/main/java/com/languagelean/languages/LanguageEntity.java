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
    @OneToMany(mappedBy = "language", fetch = FetchType.LAZY)
    @OrderBy("id.typeId ASC")
    private List<LanguageReviewTypeEntity> reviewTypes = new ArrayList<>();

    /** 仅供 JPA 反射创建实体。 */
    protected LanguageEntity() {}
    String getCode() { return code; }
    String getDisplayName() { return displayName; }
    String getPronunciationLocale() { return pronunciationLocale; }
    List<LanguageReviewTypeEntity> getReviewTypes() { return reviewTypes; }
}
