package com.vn.service;

import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.dto.staff.member.response.StaffMemberDetailResponse;
import com.vn.dto.staff.member.response.StaffMemberListItemResponse;
import org.springframework.data.domain.Page;

public interface StaffMemberService {
    // Search các member
    Page<StaffMemberListItemResponse> searchMembers(String q,
                                                    String status,
                                                    Boolean hasOverdue,
                                                    int page,
                                                    int size);
    // Trả về thông tin chi tiet của user
    StaffMemberDetailResponse getMemberDetail(Long memberId);
    //  Trả về số nợ của user
    Page<StaffLoanResponse> getMemberLoans(Long memberId,
                                           String status,
                                           Boolean openOnly,
                                           Boolean overdue,
                                           int page,
                                           int size);
}
