package com.vn.service;

import com.vn.dto.review.request.CreateReviewRequest;
import com.vn.dto.review.request.UpdateReviewRequest;
import com.vn.dto.review.response.BookReviewResponse;
import com.vn.dto.review.response.BookReviewStatsResponse;
import org.springframework.data.domain.Page;

import java.util.Optional;

public interface BookReviewService {

    Page<BookReviewResponse> getBookReviews(Long bookId, int page, int size);

    BookReviewStatsResponse getBookReviewStats(Long bookId);

    BookReviewResponse createReview(Long bookId, Long memberId, CreateReviewRequest request);

    BookReviewResponse updateReview(Long bookId, Long memberId, UpdateReviewRequest request);

    void deleteReview(Long bookId, Long memberId);

    Optional<BookReviewResponse> getMyReview(Long bookId, Long memberId);
}
