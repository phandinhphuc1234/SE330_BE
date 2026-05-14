package com.vn.dto.member.response;

import com.vn.enums.MemberRole;
import com.vn.enums.MemberStatus;

import java.time.Instant;
// DTO cho yêu cầu lấy tài khoản cá nhân
public record MyProfileResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        MemberRole role,
        MemberStatus status,
        Integer maxBorrowLimit,
        Instant membershipExpiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}

