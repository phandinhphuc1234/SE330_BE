package com.vn.dto.auth.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistrationRequest
    (@NotBlank(message = "Họ tên không được để trống")
       @Size(min = 2, max = 100, message = "Họ tên từ 2-100 ký tự")
       String fullName,

      @NotBlank(message = "Email không được để trống")
       @Email(message = "Email không hợp lệ")
       String email,

      @NotBlank(message = "Mật khẩu không được để trống")
       @Size(min = 8, message = "Mật khẩu tối thiểu 8 ký tự")
       @Pattern(
               regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
               message = "Mật khẩu phải có chữ hoa, chữ thường và số"
       )
       String password,

      @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự")
       String phone) {
}

