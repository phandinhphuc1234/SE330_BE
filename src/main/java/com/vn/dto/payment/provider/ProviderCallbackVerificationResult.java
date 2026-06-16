package com.vn.dto.payment.provider;

import com.vn.enums.PaymentProvider;

import java.time.Instant;
import java.util.Map;

/**
 * Callback/IPN đã được provider client verify và chuẩn hóa về contract chung.
 * Business service chỉ đọc DTO này, không đọc trực tiếp tham số VNPAY.
 */
public record ProviderCallbackVerificationResult(
        PaymentProvider provider,
        boolean signatureValid,
        String providerOrderId,
        String providerTransactionId,
        Long amount,
        String currency,
        boolean paymentSuccess,
        String responseCode,
        String transactionStatus,
        Instant paidAt,
        Map<String, Object> providerMetadata,
        Map<String, Object> rawPayload
) {
}
