package com.vn.controller;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.ebook.request.BorrowEbookRequest;
import com.vn.dto.ebook.response.EbookLoanResponse;
import com.vn.security.MemberUserDetails;
import com.vn.service.EbookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Ebook Loan Controller
 *
 * Endpoints (tất cả dưới /api/user/ebook-loans):
 *
 *   POST   /api/user/ebook-loans          – Mượn ebook
 *   GET    /api/user/ebook-loans          – Danh sách ebook đang mượn + lịch sử (query ?history=true)
 *   POST   /api/user/ebook-loans/{id}/return  – Trả sớm
 *   POST   /api/user/ebook-loans/{id}/renew   – Gia hạn
 *   GET    /api/user/ebook-loans/{id}     – Chi tiết (có ebookReadUrl nếu ACTIVE)
 */
@RestController
@RequestMapping("/api/user/ebook-loans")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MEMBER')")
public class EbookController {

    private final EbookService ebookService;

    /**
     * Mượn một ebook.
     * Body: { "bookId": 42 }
     */
    @PostMapping
    public ResponseEntity<ApiResponse<EbookLoanResponse>> borrowEbook(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody BorrowEbookRequest request) {

        EbookLoanResponse response = ebookService.borrowEbook(userDetails.getMember().getId(), request);
        return ResponseEntity.ok(ApiResponse.success("Mượn ebook thành công", response));
    }

    /**
     * Lấy danh sách ebook:
     *   - ?history=false (default) → chỉ ACTIVE
     *   - ?history=true            → tất cả (bao gồm EXPIRED, RETURNED)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<EbookLoanResponse>>> getMyEbookLoans(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(defaultValue = "false") boolean history,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Long memberId = userDetails.getMember().getId();

        Page<EbookLoanResponse> result = history
                ? ebookService.getMyEbookHistory(memberId, pageable)
                : ebookService.getMyEbookLoans(memberId, pageable);

        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách ebook thành công",
                result.getContent(),
                PageMeta.from(result)
        ));
    }

    /**
     * Trả sớm ebook.
     */
    @PostMapping("/{loanId}/return")
    public ResponseEntity<ApiResponse<EbookLoanResponse>> returnEbook(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable Long loanId) {

        EbookLoanResponse response = ebookService.returnEbook(userDetails.getMember().getId(), loanId);
        return ResponseEntity.ok(ApiResponse.success("Trả ebook thành công", response));
    }

    /**
     * Gia hạn ebook thêm 14 ngày.
     */
    @PostMapping("/{loanId}/renew")
    public ResponseEntity<ApiResponse<EbookLoanResponse>> renewEbook(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable Long loanId) {

        EbookLoanResponse response = ebookService.renewEbook(userDetails.getMember().getId(), loanId);
        return ResponseEntity.ok(ApiResponse.success("Gia hạn ebook thành công", response));
    }
}
