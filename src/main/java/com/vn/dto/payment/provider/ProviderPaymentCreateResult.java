package com.vn.dto.payment.provider;

import com.vn.enums.PaymentProvider;

import java.time.Instant;
import java.util.Map;

/**
 * Kết quả generic sau khi provider tạo URL thanh toán.
 * Provider-specific metadata được giữ riêng để PaymentService không phụ thuộc VNPAY/MoMo/ZaloPay.
 */
public record ProviderPaymentCreateResult(
        PaymentProvider provider,
        String providerOrderId,
        String paymentUrl,
        Instant expiredAt,
        Map<String, Object> providerMetadata
) {
}
