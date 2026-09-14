package com.vn.dto.auth.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailCodeRequest(
        @NotBlank(message = "Email không được để trống")
        @Email(message = "Email không hợp lệ")
        String email,

        @NotBlank(message = "Mã xác thực không được để trống")
        @Pattern(regexp = "^\\d{9}$", message = "Mã xác thực phải gồm đúng 9 chữ số")
        String code
) {
}
