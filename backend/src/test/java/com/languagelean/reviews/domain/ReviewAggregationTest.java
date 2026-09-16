package com.languagelean.reviews.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewAggregationTest {
    @Test void retryPassDoesNotEraseFailure() {
        var trials = List.of(Rating.AGAIN, Rating.GOOD);
        assertTrue(ReviewAggregation.passed(trials));
        assertEquals(Rating.AGAIN, ReviewAggregation.worst(trials));
    }
    @Test void failureAloneDoesNotCompleteReview() {
        assertFalse(ReviewAggregation.passed(List.of(Rating.AGAIN)));
        assertThrows(IllegalArgumentException.class, () -> ReviewAggregation.worst(List.of()));
    }
}
