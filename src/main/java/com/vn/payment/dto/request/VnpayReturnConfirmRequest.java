package com.vn.payment.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * Frontend forwards all VNPAY return query params for backend verification.
 */
public record VnpayReturnConfirmRequest(
        @NotEmpty
        Map<String, String> params
) {
}
