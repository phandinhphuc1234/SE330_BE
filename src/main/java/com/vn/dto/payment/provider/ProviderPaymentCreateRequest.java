package com.vn.dto.payment.provider;

import java.time.Instant;

/**
 * Request generic mà PaymentService gửi xuống provider client.
 * Provider cụ thể tự map sang tham số riêng của cổng thanh toán.
 */
public record ProviderPaymentCreateRequest(
        String paymentCode,
        Long amount,
        String currency,
        String description,
        String clientIp,
        String returnUrl,
        String ipnUrl,
        String bankCode,
        String locale,
        Instant expiredAt
) {
}
