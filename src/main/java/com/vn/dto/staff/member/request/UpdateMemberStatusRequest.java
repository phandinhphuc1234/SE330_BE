package com.vn.dto.staff.member.request;

import com.vn.enums.MemberStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMemberStatusRequest(
        @NotNull(message = "Trạng thái không được để trống") MemberStatus status,
        @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason
) {
}
