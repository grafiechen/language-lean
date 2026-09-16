package com.languagelean.languages;

import jakarta.persistence.*;

/** 某种语言已启用的题型及其前后端契约版本。 */
@Entity
@Table(name = "language_review_type")
class LanguageReviewTypeEntity {
    @EmbeddedId
    private LanguageReviewTypeId id;
    @MapsId("languageCode")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "language_code", nullable = false)
    private LanguageEntity language;
    @Column(name = "contract_version", nullable = false)
    private int contractVersion;
    @Column(nullable = false)
    private boolean enabled;

    /** 仅供 JPA 反射创建实体。 */
    protected LanguageReviewTypeEntity() {}
    String getTypeId() { return id.getTypeId(); }
    int getContractVersion() { return contractVersion; }
    boolean isEnabled() { return enabled; }
}
