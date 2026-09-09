package com.vn.service.impl;

import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.dto.staff.member.internal.StaffMemberStats;
import com.vn.dto.staff.member.request.UpdateMemberStatusRequest;
import com.vn.dto.staff.member.response.MemberStatusUpdateResponse;
import com.vn.dto.staff.member.response.StaffMemberDetailResponse;
import com.vn.dto.staff.member.response.StaffMemberListItemResponse;
import com.vn.entity.Member;
import com.vn.entity.MemberStatusAudit;
import com.vn.enums.BorrowStatus;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.StaffMemberMapper;
import com.vn.repository.MemberRepository;
import com.vn.repository.MemberStatusAuditRepository;
import com.vn.security.JwtService;
import com.vn.service.RedisTokenService;
import com.vn.service.StaffLoanService;
import com.vn.service.StaffMemberService;
import com.vn.service.impl.staff.member.StaffMemberStatsLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StaffMemberServiceImpl implements StaffMemberService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository memberRepository;
    private final StaffLoanService staffLoanService;
    private final StaffMemberStatsLoader statsLoader;
    private final StaffMemberMapper staffMemberMapper;
    private final MemberStatusAuditRepository memberStatusAuditRepository;
    private final RedisTokenService redisTokenService;
    private final JwtService jwtService;

    // Tìm member theo filter của staff, sau đó load thống kê phụ theo batch và map sang response.
    @Override
    @Transactional(readOnly = true)
    public Page<StaffMemberListItemResponse> searchMembers(String q,
                                                           String status,
                                                           Boolean hasOverdue,
                                                           int page,
                                                           int size) {
        Instant now = Instant.now();
        Page<Member> members = memberRepository.searchStaffMembers(
                normalizeLikeQuery(q),
                parseOptionalLong(q),
                parseMemberStatus(status),
                hasOverdue,
                BorrowStatus.OVERDUE,
                BorrowStatus.BORROWED,
                now,
                buildMemberPageable(page, size)
        );

        Map<Long, StaffMemberStats> statsByMemberId = statsLoader.loadStats(members.getContent(), now);
        return members.map(member -> staffMemberMapper.toListItem(
                member,
                statsByMemberId.getOrDefault(member.getId(), StaffMemberStats.empty())
        ));
    }

    // Lấy hồ sơ một member kèm summary borrow/hold/fine cho trang staff member detail.
    @Override
    @Transactional(readOnly = true)
    public StaffMemberDetailResponse getMemberDetail(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        Instant now = Instant.now();
        Map<Long, StaffMemberStats> statsByMemberId = statsLoader.loadStats(List.of(member), now);
        return staffMemberMapper.toDetail(
                member,
                statsByMemberId.getOrDefault(member.getId(), StaffMemberStats.empty())
        );
    }

    // Lấy open loans hoặc borrow history của một member; phần query loan được tái sử dụng từ StaffLoanServiceImpl.
    @Override
    @Transactional(readOnly = true)
    public Page<StaffLoanResponse> getMemberLoans(Long memberId,
                                                  String status,
                                                  Boolean openOnly,
                                                  Boolean overdue,
                                                  int page,
                                                  int size) {
        if (!memberRepository.existsById(memberId)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return staffLoanService.searchMemberLoans(memberId, status, openOnly, overdue, page, size);
    }

    @Override
    @Transactional
    public MemberStatusUpdateResponse updateMemberStatus(Long actorMemberId,
                                                         Long memberId,
                                                         UpdateMemberStatusRequest request) {
        if (actorMemberId.equals(memberId)) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWN_STATUS);
        }
        if (request.status() == MemberStatus.PENDING_VERIFICATION) {
            throw new AppException(ErrorCode.INVALID_MEMBER_STATUS_TRANSITION);
        }

        Member member = memberRepository.findLockedById(memberId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        MemberStatus previousStatus = member.getStatus();
        if (previousStatus == request.status()) {
            throw new AppException(ErrorCode.INVALID_MEMBER_STATUS_TRANSITION);
        }

        Instant changedAt = Instant.now();
        String reason = normalizeReason(request.reason());
        member.setStatus(request.status());
        memberStatusAuditRepository.save(MemberStatusAudit.builder()
                .memberId(member.getId())
                .actorMemberId(actorMemberId)
                .previousStatus(previousStatus)
                .newStatus(request.status())
                .reason(reason)
                .createdAt(changedAt)
                .build());

        // Revoke active/refresh tokens both when locking and when reactivating a
        // member, so an older token can never become valid again after reactivation.
        redisTokenService.revokeAllSessions(member.getId(), jwtService.getRefreshExpiry());
        return new MemberStatusUpdateResponse(member.getId(), previousStatus, request.status(), reason, changedAt);
    }

    // Parse status query param về enum của domain, trả lỗi chuẩn nếu client truyền sai.
    private MemberStatus parseMemberStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return MemberStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    // Page của staff members cố định sort mới nhất trước trong phase MVP.
    private Pageable buildMemberPageable(int page, int size) {
        return PageRequest.of(
                Math.max(page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
    }

    // Giới hạn page size để tránh request danh sách quá lớn.
    private int normalizeSize(int size) {
        int requestedSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }

    // Chuẩn hóa keyword search cho query LIKE không phân biệt hoa thường.
    private String normalizeLikeQuery(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase() + "%";
    }

    // Nếu q là số thì cho phép search theo member id, còn text thường thì bỏ qua nhánh id.
    private Long parseOptionalLong(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(q.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.trim();
    }
}
