package com.vn.book.dto.review.response;

import java.util.Map;

public record BookReviewStatsResponse(
        Long bookId,
        Double averageRating,
        Long totalReviews,
        Map<Integer, Long> ratingDistribution
) {
}
