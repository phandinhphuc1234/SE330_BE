package com.vn.controller;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.common.PageMeta;
import com.vn.dto.payment.response.AdminPaymentRowResponse;
import com.vn.dto.payment.response.PaymentDashboardSummaryResponse;
import com.vn.dto.payment.response.PaymentReceiptResponse;
import com.vn.service.PaymentReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentReceiptService paymentReceiptService;

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PaymentDashboardSummaryResponse>> getPaymentSummary() {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy tổng quan thanh toán thành công",
                paymentReceiptService.getDashboardSummary()
        ));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<AdminPaymentRowResponse>>> searchPayments(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paidFrom,
            @RequestParam(required = false) String paidTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AdminPaymentRowResponse> payments = paymentReceiptService.searchAdminPayments(
                q,
                status,
                paidFrom,
                paidTo,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách thanh toán thành công",
                payments.getContent(),
                PageMeta.from(payments)
        ));
    }

    @GetMapping("/receipts/{paymentCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PaymentReceiptResponse>> getReceipt(@PathVariable String paymentCode) {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy biên lai thanh toán thành công",
                paymentReceiptService.getAdminReceipt(paymentCode)
        ));
    }
}
