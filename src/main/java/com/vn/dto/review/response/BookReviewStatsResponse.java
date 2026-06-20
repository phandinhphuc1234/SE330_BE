package com.vn.dto.review.response;

import java.util.Map;

public record BookReviewStatsResponse(
        Long bookId,
        Double averageRating,
        Long totalReviews,
        Map<Integer, Long> ratingDistribution
) {}
