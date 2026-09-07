package com.vn.repository.projection;

public interface BookReviewStatsProjection {

    Long getBookId();

    Double getAverageRating();

    Long getTotalReviews();
}
