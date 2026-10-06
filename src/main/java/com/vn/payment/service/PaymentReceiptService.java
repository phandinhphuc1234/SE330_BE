package com.vn.payment.service;

import com.vn.payment.dto.response.AdminPaymentRowResponse;
import com.vn.payment.dto.response.PaymentDashboardSummaryResponse;
import com.vn.payment.dto.response.PaymentReceiptResponse;
import org.springframework.data.domain.Page;

public interface PaymentReceiptService {

    Page<PaymentReceiptResponse> getMemberReceipts(Long memberId, int page, int size);

    PaymentReceiptResponse getMemberReceipt(Long memberId, String paymentCode);

    Page<AdminPaymentRowResponse> searchAdminPayments(String q,
                                                      String status,
                                                      String paidFrom,
                                                      String paidTo,
                                                      int page,
                                                      int size);

    PaymentReceiptResponse getAdminReceipt(String paymentCode);

    PaymentDashboardSummaryResponse getDashboardSummary();
}
