package com.vn.book.service;

import com.vn.book.dto.review.request.CreateReviewRequest;
import com.vn.book.dto.review.request.UpdateReviewRequest;
import com.vn.book.dto.review.response.BookReviewResponse;
import com.vn.book.dto.review.response.BookReviewStatsResponse;
import org.springframework.data.domain.Page;

import java.util.Optional;

public interface BookReviewService {

    Page<BookReviewResponse> getBookReviews(Long bookId, int page, int size);

    BookReviewStatsResponse getBookReviewStats(Long bookId);

    Optional<BookReviewResponse> getMyReview(Long bookId, Long memberId);

    BookReviewResponse createReview(Long bookId, Long memberId, CreateReviewRequest request);

    BookReviewResponse updateReview(Long bookId, Long memberId, UpdateReviewRequest request);

    void deleteReview(Long bookId, Long memberId);
}
