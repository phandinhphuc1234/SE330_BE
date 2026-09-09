package com.vn.service.staff;

import com.vn.dto.staff.member.request.UpdateMemberStatusRequest;
import com.vn.dto.staff.member.response.MemberStatusUpdateResponse;
import com.vn.entity.Member;
import com.vn.entity.MemberStatusAudit;
import com.vn.enums.MemberStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.StaffMemberMapper;
import com.vn.repository.MemberRepository;
import com.vn.repository.MemberStatusAuditRepository;
import com.vn.security.JwtService;
import com.vn.service.RedisTokenService;
import com.vn.service.StaffLoanService;
import com.vn.service.impl.StaffMemberServiceImpl;
import com.vn.service.impl.staff.member.StaffMemberStatsLoader;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffMemberStatusServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private StaffLoanService staffLoanService;
    @Mock private StaffMemberStatsLoader statsLoader;
    @Mock private StaffMemberMapper staffMemberMapper;
    @Mock private MemberStatusAuditRepository memberStatusAuditRepository;
    @Mock private RedisTokenService redisTokenService;
    @Mock private JwtService jwtService;

    private StaffMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffMemberServiceImpl(memberRepository, staffLoanService, statsLoader, staffMemberMapper,
                memberStatusAuditRepository, redisTokenService, jwtService);
    }

    @Test
    void updateMemberStatus_shouldWriteAuditAndRevokeSessions() {
        Member member = TestDataFactory.activeMember(2L);
        when(memberRepository.findLockedById(2L)).thenReturn(Optional.of(member));
        when(jwtService.getRefreshExpiry()).thenReturn(604800000L);

        MemberStatusUpdateResponse response = service.updateMemberStatus(1L, 2L,
                new UpdateMemberStatusRequest(MemberStatus.BANNED, "Repeated policy breach"));

        assertThat(member.getStatus()).isEqualTo(MemberStatus.BANNED);
        assertThat(response.previousStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(response.newStatus()).isEqualTo(MemberStatus.BANNED);
        ArgumentCaptor<MemberStatusAudit> auditCaptor = ArgumentCaptor.forClass(MemberStatusAudit.class);
        verify(memberStatusAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActorMemberId()).isEqualTo(1L);
        assertThat(auditCaptor.getValue().getReason()).isEqualTo("Repeated policy breach");
        verify(redisTokenService).revokeAllSessions(2L, 604800000L);
    }

    @Test
    void updateMemberStatus_shouldRejectSelfStatusChange() {
        assertThatThrownBy(() -> service.updateMemberStatus(1L, 1L,
                new UpdateMemberStatusRequest(MemberStatus.BANNED, "No")))
                .isInstanceOfSatisfying(AppException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CANNOT_CHANGE_OWN_STATUS.getCode()));

        verify(memberRepository, never()).findLockedById(anyLong());
        verify(redisTokenService, never()).revokeAllSessions(anyLong(), anyLong());
    }
}
