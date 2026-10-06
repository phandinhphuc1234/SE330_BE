package com.vn.payment.service;

import com.vn.payment.dto.request.CreatePaymentRequest;
import com.vn.payment.dto.response.CreatePaymentResponse;
import com.vn.payment.dto.response.PaymentResponse;

public interface PaymentService {

    // Tạo giao dịch thanh toán PENDING và trả paymentUrl, không cấp quyền đọc ebook.
    CreatePaymentResponse createPayment(Long memberId,
                                        String idempotencyKey,
                                        String clientIp,
                                        CreatePaymentRequest request);

    // Chỉ trả payment thuộc member hiện tại để tránh lộ trạng thái giao dịch của user khác.
    PaymentResponse getPayment(Long memberId, Long paymentId);

    // Dùng cho frontend poll theo mã payment sau redirect từ provider.
    PaymentResponse getPaymentByCode(Long memberId, String paymentCode);
}
