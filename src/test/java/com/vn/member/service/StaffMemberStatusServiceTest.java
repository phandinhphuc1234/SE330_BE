package com.vn.member.service;

import com.vn.member.dto.staff.request.UpdateMemberStatusRequest;
import com.vn.member.dto.staff.response.MemberStatusUpdateResponse;
import com.vn.member.entity.Member;
import com.vn.member.entity.MemberStatusAudit;
import com.vn.member.enums.MemberStatus;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.member.mapper.StaffMemberMapper;
import com.vn.member.repository.MemberRepository;
import com.vn.member.repository.MemberStatusAuditRepository;
import com.vn.auth.security.JwtService;
import com.vn.auth.service.RedisTokenService;
import com.vn.notification.service.NotificationQueueService;
import com.vn.loan.service.StaffLoanService;
import com.vn.member.service.impl.StaffMemberServiceImpl;
import com.vn.member.service.impl.staff.StaffMemberStatsLoader;
import com.vn.notification.service.EmailNotificationCommand;
import com.vn.shared.testsupport.TestDataFactory;
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
    @Mock private NotificationQueueService notificationQueueService;

    private StaffMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffMemberServiceImpl(memberRepository, staffLoanService, statsLoader, staffMemberMapper,
                memberStatusAuditRepository, redisTokenService, jwtService, notificationQueueService,
                com.vn.shared.testsupport.TestTime.CLOCK);
    }

    @Test
    void updateMemberStatus_shouldWriteAuditAndRevokeSessions() {
        Member member = TestDataFactory.activeMember(2L);
        when(memberRepository.findLockedById(2L)).thenReturn(Optional.of(member));
        when(jwtService.getRefreshExpiry()).thenReturn(604800000L);
        when(memberStatusAuditRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    MemberStatusAudit audit = invocation.getArgument(0);
                    audit.setId(44L);
                    return audit;
                });

        MemberStatusUpdateResponse response = service.updateMemberStatus(1L, 2L,
                new UpdateMemberStatusRequest(MemberStatus.BANNED, "Repeated policy breach"));

        assertThat(member.getStatus()).isEqualTo(MemberStatus.BANNED);
        assertThat(response.previousStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(response.newStatus()).isEqualTo(MemberStatus.BANNED);
        ArgumentCaptor<MemberStatusAudit> auditCaptor = ArgumentCaptor.forClass(MemberStatusAudit.class);
        verify(memberStatusAuditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActorMemberId()).isEqualTo(1L);
        assertThat(auditCaptor.getValue().getReason()).isEqualTo("Repeated policy breach");
        ArgumentCaptor<EmailNotificationCommand> notificationCaptor =
                ArgumentCaptor.forClass(EmailNotificationCommand.class);
        verify(notificationQueueService).enqueueEmail(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().eventKey())
                .isEqualTo("ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:44:EMAIL");
        assertThat(notificationCaptor.getValue().payload())
                .containsEntry("reason", "Repeated policy breach");
        verify(redisTokenService).revokeAllSessions(2L, 604800000L);
    }

    @Test
    void updateMemberStatus_shouldEnqueueReactivationNotification() {
        Member member = TestDataFactory.activeMember(2L);
        member.setStatus(MemberStatus.BANNED);
        when(memberRepository.findLockedById(2L)).thenReturn(Optional.of(member));
        when(jwtService.getRefreshExpiry()).thenReturn(604800000L);
        when(memberStatusAuditRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    MemberStatusAudit audit = invocation.getArgument(0);
                    audit.setId(45L);
                    return audit;
                });

        service.updateMemberStatus(1L, 2L,
                new UpdateMemberStatusRequest(MemberStatus.ACTIVE, "Appeal approved"));

        ArgumentCaptor<EmailNotificationCommand> notificationCaptor =
                ArgumentCaptor.forClass(EmailNotificationCommand.class);
        verify(notificationQueueService).enqueueEmail(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().eventKey())
                .isEqualTo("ACCOUNT_REACTIVATED:MEMBER_STATUS_AUDIT:45:EMAIL");
        assertThat(notificationCaptor.getValue().templateCode()).isEqualTo("account-reactivated");
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
        verify(notificationQueueService, never()).enqueueEmail(org.mockito.ArgumentMatchers.any());
    }
}
