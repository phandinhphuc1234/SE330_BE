package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.review.request.CreateReviewRequest;
import com.vn.dto.review.request.UpdateReviewRequest;
import com.vn.dto.review.response.BookReviewResponse;
import com.vn.dto.review.response.BookReviewStatsResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Book Reviews", description = "Public book ratings and authenticated member review management")
public interface BookReviewApiDocs {

    @Operation(summary = "List reviews", description = "Return newest reviews first with pagination metadata.")
    ResponseEntity<ApiResponse<List<BookReviewResponse>>> getBookReviews(
            @Parameter(description = "Book ID") Long bookId,
            @Parameter(description = "Page number (0-based)") int page,
            @Parameter(description = "Page size (1-100)") int size
    );

    @Operation(summary = "Get rating statistics", description = "Return average, total and 1-to-5-star distribution.")
    ResponseEntity<ApiResponse<BookReviewStatsResponse>> getBookReviewStats(Long bookId);

    @Operation(summary = "Get my review")
    ResponseEntity<ApiResponse<BookReviewResponse>> getMyReview(
            Long bookId,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @Operation(summary = "Create my review")
    ResponseEntity<ApiResponse<BookReviewResponse>> createReview(
            Long bookId,
            CreateReviewRequest request,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @Operation(summary = "Update my review")
    ResponseEntity<ApiResponse<BookReviewResponse>> updateReview(
            Long bookId,
            UpdateReviewRequest request,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @Operation(summary = "Delete my review")
    ResponseEntity<ApiResponse<Void>> deleteReview(
            Long bookId,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );
}
