package com.vn.dto.review.response;

import java.time.Instant;

public record BookReviewResponse(
        Long reviewId,
        Long bookId,
        Long memberId,
        String memberName,
        Integer rating,
        String content,
        Instant createdAt,
        Instant updatedAt
) {}
