package com.vn.controller;

import com.vn.controller.docs.BookReviewApiDocs;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.review.request.CreateReviewRequest;
import com.vn.dto.review.request.UpdateReviewRequest;
import com.vn.dto.review.response.BookReviewResponse;
import com.vn.dto.review.response.BookReviewStatsResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.BookReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/books/{bookId}/reviews")
@RequiredArgsConstructor
public class BookReviewController implements BookReviewApiDocs {

    private final BookReviewService bookReviewService;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<List<BookReviewResponse>>> getBookReviews(
            @PathVariable Long bookId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<BookReviewResponse> reviews = bookReviewService.getBookReviews(bookId, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách đánh giá thành công",
                reviews.getContent(),
                PageMeta.from(reviews)
        ));
    }

    @Override
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<BookReviewStatsResponse>> getBookReviewStats(@PathVariable Long bookId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy thống kê đánh giá thành công",
                bookReviewService.getBookReviewStats(bookId)
        ));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<BookReviewResponse>> getMyReview(
            @PathVariable Long bookId,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        return bookReviewService.getMyReview(bookId, getCurrentMemberId(userDetails))
                .map(review -> ResponseEntity.ok(ApiResponse.success("Lấy đánh giá của bạn thành công", review)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(
                        "Bạn chưa đánh giá sách này", (BookReviewResponse) null
                )));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @PostMapping
    public ResponseEntity<ApiResponse<BookReviewResponse>> createReview(
            @PathVariable Long bookId,
            @Valid @RequestBody CreateReviewRequest request,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        BookReviewResponse review = bookReviewService.createReview(
                bookId, getCurrentMemberId(userDetails), request
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo đánh giá thành công", review));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @PutMapping("/my")
    public ResponseEntity<ApiResponse<BookReviewResponse>> updateReview(
            @PathVariable Long bookId,
            @Valid @RequestBody UpdateReviewRequest request,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        return ResponseEntity.ok(ApiResponse.success(
                "Cập nhật đánh giá thành công",
                bookReviewService.updateReview(bookId, getCurrentMemberId(userDetails), request)
        ));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @DeleteMapping("/my")
    public ResponseEntity<ApiResponse<Void>> deleteReview(
            @PathVariable Long bookId,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        bookReviewService.deleteReview(bookId, getCurrentMemberId(userDetails));
        return ResponseEntity.ok(ApiResponse.success("Xóa đánh giá thành công", null));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null || userDetails.getMember() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}
