package com.vn.payment.controller;

import com.vn.shared.dto.ApiResponse;
import com.vn.shared.dto.PageMeta;
import com.vn.payment.dto.response.PaymentReceiptResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.auth.security.MemberUserDetails;
import com.vn.payment.service.PaymentReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments/receipts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MEMBER')")
public class PaymentReceiptController {

    private final PaymentReceiptService paymentReceiptService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentReceiptResponse>>> getMyReceipts(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PaymentReceiptResponse> receipts = paymentReceiptService.getMemberReceipts(
                getCurrentMemberId(userDetails),
                page,
                size
        );
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách biên lai thanh toán thành công",
                receipts.getContent(),
                PageMeta.from(receipts)
        ));
    }

    @GetMapping("/{paymentCode}")
    public ResponseEntity<ApiResponse<PaymentReceiptResponse>> getMyReceipt(
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @PathVariable String paymentCode) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy biên lai thanh toán thành công",
                paymentReceiptService.getMemberReceipt(getCurrentMemberId(userDetails), paymentCode)
        ));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }
}
