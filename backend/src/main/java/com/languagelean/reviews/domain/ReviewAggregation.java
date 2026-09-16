package com.languagelean.reviews.domain;

import java.util.List;

/** 汇总同一词条一次复习中的失败、重试和多个题型结果。 */
public final class ReviewAggregation {
    private ReviewAggregation() {}

    /** 返回所有尝试中的最差评分，确保重试成功不会抹掉之前的 Again。 */
    public static Rating worst(List<Rating> trials) {
        if (trials == null || trials.isEmpty() || trials.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("A completed review requires rated trials");
        }
        return trials.stream().min(Enum::compareTo).orElseThrow();
    }

    /** 至少出现一次 Hard 或 Good 时，该题型才算完成。 */
    public static boolean passed(List<Rating> trials) {
        return trials != null && trials.stream().anyMatch(r -> r == Rating.HARD || r == Rating.GOOD);
    }
}
