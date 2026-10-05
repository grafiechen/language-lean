package com.languagelean.learning;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

/** 单词本与共享学习条目的复合主键。 */
@Embeddable
class WordbookLearningItemId implements Serializable {
    UUID wordbookId;
    UUID learningItemId;

    protected WordbookLearningItemId() {}

    WordbookLearningItemId(UUID wordbookId, UUID learningItemId) {
        this.wordbookId = wordbookId;
        this.learningItemId = learningItemId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WordbookLearningItemId that)) return false;
        return java.util.Objects.equals(wordbookId, that.wordbookId)
                && java.util.Objects.equals(learningItemId, that.learningItemId);
    }

    @Override
    public int hashCode() { return java.util.Objects.hash(wordbookId, learningItemId); }
}
