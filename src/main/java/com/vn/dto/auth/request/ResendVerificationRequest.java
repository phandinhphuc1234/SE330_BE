package com.vn.dto.auth.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
// DTO chứa request gửi lại email xác nhận
public record ResendVerificationRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không hợp lệ")
        String email
) {
}

