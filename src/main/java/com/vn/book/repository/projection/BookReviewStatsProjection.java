package com.vn.book.repository.projection;

public interface BookReviewStatsProjection {

    Long getBookId();

    Double getAverageRating();

    Long getTotalReviews();
}
