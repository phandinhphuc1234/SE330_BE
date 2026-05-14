package com.vn.dto.member.request;

import jakarta.validation.constraints.Size;
// DTO cho yêu cầu update profile
public record UpdateMyProfileRequest(
        @Size(min = 2, max = 100, message = "Họ tên từ 2-100 ký tự")
        String fullName,

        @Size(max = 20, message = "Số điện thoại tối đa 20 ký tự")
        String phone
) {
}

