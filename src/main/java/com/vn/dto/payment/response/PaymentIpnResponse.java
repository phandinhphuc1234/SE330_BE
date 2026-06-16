package com.vn.dto.payment.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response trực tiếp cho provider IPN. Không dùng ApiResponse vì VNPAY yêu cầu key RspCode/Message.
 */
public record PaymentIpnResponse(
        @JsonProperty("RspCode")
        String rspCode,

        @JsonProperty("Message")
        String message
) {
    public static PaymentIpnResponse confirmSuccess() {
        return new PaymentIpnResponse("00", "Confirm Success");
    }

    public static PaymentIpnResponse alreadyConfirmed() {
        return new PaymentIpnResponse("02", "Order already confirmed");
    }

    public static PaymentIpnResponse orderNotFound() {
        return new PaymentIpnResponse("01", "Order not found");
    }

    public static PaymentIpnResponse invalidAmount() {
        return new PaymentIpnResponse("04", "Invalid amount");
    }

    public static PaymentIpnResponse invalidSignature() {
        return new PaymentIpnResponse("97", "Invalid signature");
    }

    public static PaymentIpnResponse unknownError() {
        return new PaymentIpnResponse("99", "Unknown error");
    }
}
