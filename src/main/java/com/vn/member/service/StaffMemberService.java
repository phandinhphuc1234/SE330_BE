package com.vn.member.service;

import com.vn.loan.dto.staff.loan.response.StaffLoanResponse;
import com.vn.member.dto.staff.response.StaffMemberDetailResponse;
import com.vn.member.dto.staff.response.StaffMemberListItemResponse;
import com.vn.member.dto.staff.request.UpdateMemberStatusRequest;
import com.vn.member.dto.staff.response.MemberStatusUpdateResponse;
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

    MemberStatusUpdateResponse updateMemberStatus(Long actorMemberId,
                                                   Long memberId,
                                                   UpdateMemberStatusRequest request);
}
