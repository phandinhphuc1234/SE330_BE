package com.vn.controller;

import com.vn.controller.docs.HoldApiDocs;
import com.vn.dto.circulation.request.CreateHoldRequest;
import com.vn.dto.circulation.response.BorrowResponse;
import com.vn.dto.circulation.response.HoldResponse;
import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.enums.MemberRole;
import com.vn.enums.ReservationStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.security.MemberUserDetails;
import com.vn.service.HoldService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HoldController implements HoldApiDocs {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final HoldService holdService;

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @PostMapping("/holds")
    public ResponseEntity<ApiResponse<HoldResponse>> createHold(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody CreateHoldRequest request) {
        HoldResponse hold = holdService.createHold(getCurrentMemberId(userDetails), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đặt giữ chỗ sách thành công", hold));
    }

    @Override
    @PreAuthorize("hasRole('MEMBER')")
    @GetMapping("/holds/my")
    public ResponseEntity<ApiResponse<List<HoldResponse>>> getMyHolds(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<HoldResponse> holds = holdService.getMyHolds(
                getCurrentMemberId(userDetails),
                parseStatus(status),
                page,
                size
        );
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách giữ chỗ thành công",
                holds.getContent(),
                PageMeta.from(holds)
        ));
    }

    @Override
    @PreAuthorize("hasAnyRole('MEMBER', 'LIBRARIAN', 'ADMIN')")
    @DeleteMapping("/holds/{holdId}")
    public ResponseEntity<ApiResponse<HoldResponse>> cancelHold(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable Long holdId) {
        HoldResponse hold = holdService.cancelHold(
                getCurrentMemberId(userDetails),
                isStaffActor(userDetails),
                holdId
        );
        return ResponseEntity.ok(ApiResponse.success("Hủy giữ chỗ thành công", hold));
    }

    @Override
    @PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
    @PostMapping("/staff/holds/{holdId}/checkout")
    public ResponseEntity<ApiResponse<BorrowResponse>> checkoutHold(
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable Long holdId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Checkout giữ chỗ thành công",
                holdService.checkoutHold(getCurrentMemberId(userDetails), idempotencyKey, holdId)
        ));
    }

    // Chức năng: parse status query param và trả lỗi chuẩn của project nếu client truyền sai enum.
    private ReservationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    // Chức năng: xác định actor có phải staff/admin để cho phép hủy hold hộ member.
    private boolean isStaffActor(MemberUserDetails userDetails) {
        MemberRole role = userDetails.getMember().getRole();
        return role == MemberRole.LIBRARIAN || role == MemberRole.ADMIN;
    }

    // Chức năng: kiểm tra đăng nhập và lấy memberId từ principal.
    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}
