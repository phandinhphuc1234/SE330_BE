package com.vn.payment.dto.provider;

import com.vn.payment.enums.PaymentProvider;

import java.util.Map;

/**
 * Kết quả parse return URL đã normalize.
 * IPN/callback server-to-server mới là nguồn cập nhật trạng thái payment.
 */
public record ProviderReturnResult(
        PaymentProvider provider,
        boolean signatureValid,
        String providerOrderId,
        String providerTransactionId,
        String responseCode,
        Map<String, Object> rawPayload
) {
}
