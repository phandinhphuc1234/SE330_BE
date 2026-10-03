package com.vn.member.dto.response;

import com.vn.member.enums.MemberRole;
import com.vn.member.enums.MemberStatus;

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

