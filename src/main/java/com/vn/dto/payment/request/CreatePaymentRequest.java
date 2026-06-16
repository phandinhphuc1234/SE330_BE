package com.vn.dto.payment.request;

import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request tạo payment generic.
 * Frontend chỉ gửi target/provider; amount luôn do backend tự tính từ nghiệp vụ.
 */
public record CreatePaymentRequest(
        @NotNull(message = "Mục đích thanh toán là bắt buộc")
        PaymentPurpose purpose,

        @NotNull(message = "Loại tài nguyên thanh toán là bắt buộc")
        PaymentTargetType targetType,

        @NotNull(message = "ID tài nguyên thanh toán là bắt buộc")
        Long targetId,

        @NotNull(message = "Cổng thanh toán là bắt buộc")
        PaymentProvider provider,

        @Size(max = 30, message = "Mã ngân hàng/phương thức thanh toán tối đa 30 ký tự")
        String bankCode,

        @Size(max = 10, message = "Ngôn ngữ thanh toán tối đa 10 ký tự")
        String locale
) {
}
