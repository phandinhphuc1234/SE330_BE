package com.vn.dto.staff.member.response;

import com.vn.enums.MemberStatus;

import java.time.Instant;

public record MemberStatusUpdateResponse(
        Long memberId,
        MemberStatus previousStatus,
        MemberStatus newStatus,
        String reason,
        Instant changedAt
) {
}
