package com.vn.dto.payment.provider;

import com.vn.enums.PaymentProvider;

import java.util.Map;

/**
 * Payload callback/IPN raw từ provider.
 * Provider client chịu trách nhiệm verify chữ ký và normalize dữ liệu.
 */
public record ProviderCallbackRequest(
        PaymentProvider provider,
        Map<String, String> params,
        Map<String, String> headers,
        String rawBody
) {
}
