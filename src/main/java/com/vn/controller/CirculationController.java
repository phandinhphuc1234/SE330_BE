package com.vn.controller;

import com.vn.controller.docs.CirculationApiDocs;
import com.vn.dto.circulation.request.CheckinRequest;
import com.vn.dto.circulation.request.CheckoutRequest;
import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.CheckinResponse;
import com.vn.dto.circulation.response.CheckoutPreviewResponse;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.CirculationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CirculationController implements CirculationApiDocs {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CirculationService circulationService;

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping("/staff/circulation/checkouts/preview")
    public ResponseEntity<ApiResponse<CheckoutPreviewResponse>> previewCheckout(
            @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Kiểm tra điều kiện mượn sách thành công",
                circulationService.previewCheckout(request)
        ));
    }

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping("/staff/circulation/checkouts")
    public ResponseEntity<ApiResponse<BorrowResponse>> checkout(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody CheckoutRequest request) {
        // Header is optional at Spring MVC level so the service can return the project's business error format.
        return ResponseEntity.ok(ApiResponse.success(
                "Mượn sách thành công",
                circulationService.checkout(getCurrentMemberId(userDetails), idempotencyKey, request)
        ));
    }

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping("/staff/circulation/checkins")
    public ResponseEntity<ApiResponse<CheckinResponse>> checkin(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody CheckinRequest request) {
        // A missing Idempotency-Key is converted to ErrorCode.IDEMPOTENCY_KEY_REQUIRED in service layer.
        return ResponseEntity.ok(ApiResponse.success(
                "Trả sách thành công",
                circulationService.checkin(getCurrentMemberId(userDetails), idempotencyKey, request)
        ));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/borrows/my")
    public ResponseEntity<ApiResponse<List<StaffLoanResponse>>> getMyActiveBorrows(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<StaffLoanResponse> borrows = circulationService.getMyActiveBorrows(getCurrentMemberId(userDetails), page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách sách đang mượn thành công",
                borrows.getContent(),
                PageMeta.from(borrows)
        ));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/borrows/my/history")
    public ResponseEntity<ApiResponse<List<StaffLoanResponse>>> getMyBorrowHistory(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<StaffLoanResponse> borrows = circulationService.getMyBorrowHistory(getCurrentMemberId(userDetails), page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy lịch sử mượn sách thành công",
                borrows.getContent(),
                PageMeta.from(borrows)
        ));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @PutMapping("/borrows/{borrowId}/extend")
    public ResponseEntity<ApiResponse<RenewBorrowResponse>> renewMyBorrow(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable Long borrowId,
            @Valid @RequestBody RenewBorrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Gia hạn lượt mượn thành công",
                circulationService.renewMyBorrow(getCurrentMemberId(userDetails), idempotencyKey, borrowId, request)
        ));
    }

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PutMapping("/staff/borrows/{borrowId}/extend")
    public ResponseEntity<ApiResponse<RenewBorrowResponse>> staffRenewBorrow(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable Long borrowId,
            @Valid @RequestBody RenewBorrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Gia hạn lượt mượn thành công",
                circulationService.staffRenewBorrow(getCurrentMemberId(userDetails), idempotencyKey, borrowId, request)
        ));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}
