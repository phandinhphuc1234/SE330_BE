package com.vn.mapper;

import com.vn.dto.staff.member.internal.StaffMemberStats;
import com.vn.dto.staff.member.response.StaffMemberDetailResponse;
import com.vn.dto.staff.member.response.StaffMemberListItemResponse;
import com.vn.entity.Member;
import org.springframework.stereotype.Component;

@Component
public class StaffMemberMapper {

    // Map dữ liệu rút gọn cho table danh sách staff members.
    public StaffMemberListItemResponse toListItem(Member member, StaffMemberStats stats) {
        return new StaffMemberListItemResponse(
                member.getId(),
                member.getFullName(),
                member.getEmail(),
                member.getPhone(),
                member.getRole().name(),
                member.getStatus().name(),
                member.getMaxBorrowLimit(),
                member.getMembershipExpiresAt(),
                stats.activeLoansCount(),
                stats.overdueLoansCount(),
                stats.activeHoldsCount(),
                stats.unpaidFineTotal(),
                member.getCreatedAt()
        );
    }

    // Map dữ liệu tổng quan đầy đủ hơn cho trang chi tiết một member.
    public StaffMemberDetailResponse toDetail(Member member, StaffMemberStats stats) {
        return new StaffMemberDetailResponse(
                member.getId(),
                member.getFullName(),
                member.getEmail(),
                member.getPhone(),
                member.getRole().name(),
                member.getStatus().name(),
                member.getMaxBorrowLimit(),
                member.getMembershipExpiresAt(),
                stats.activeLoansCount(),
                stats.openLoansCount(),
                stats.overdueLoansCount(),
                stats.borrowHistoryCount(),
                stats.activeHoldsCount(),
                stats.unpaidFineTotal(),
                member.getCreatedAt(),
                member.getUpdatedAt()
        );
    }
}
