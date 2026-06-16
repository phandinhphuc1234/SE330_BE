package com.vn.service;

import com.vn.dto.payment.response.AdminPaymentRowResponse;
import com.vn.dto.payment.response.PaymentDashboardSummaryResponse;
import com.vn.dto.payment.response.PaymentReceiptResponse;
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
