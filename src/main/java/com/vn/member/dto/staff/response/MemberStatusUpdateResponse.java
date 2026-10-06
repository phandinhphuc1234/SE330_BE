package com.vn.member.dto.staff.response;

import com.vn.member.enums.MemberStatus;

import java.time.Instant;

public record MemberStatusUpdateResponse(
        Long memberId,
        MemberStatus previousStatus,
        MemberStatus newStatus,
        String reason,
        Instant changedAt
) {
}
