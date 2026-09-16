package com.languagelean.languages;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** language_review_type 的“语言 + 题型”复合主键。 */
@Embeddable
class LanguageReviewTypeId implements Serializable {
    @Column(name = "language_code", length = 16)
    private String languageCode;
    @Column(name = "type_id", length = 64)
    private String typeId;

    /** 仅供 JPA 反射创建复合键。 */
    protected LanguageReviewTypeId() {}
    String getTypeId() { return typeId; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LanguageReviewTypeId id)) return false;
        return Objects.equals(languageCode, id.languageCode) && Objects.equals(typeId, id.typeId);
    }
    @Override public int hashCode() { return Objects.hash(languageCode, typeId); }
}
