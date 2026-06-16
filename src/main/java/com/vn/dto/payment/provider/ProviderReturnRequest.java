package com.vn.dto.payment.provider;

import com.vn.enums.PaymentProvider;

import java.util.Map;

/**
 * Payload return URL từ provider sau khi user quay lại website.
 * Return URL chỉ dùng để hiển thị kết quả, không cập nhật nghiệp vụ thanh toán.
 */
public record ProviderReturnRequest(
        PaymentProvider provider,
        Map<String, String> params,
        Map<String, String> headers,
        String rawBody
) {
}
