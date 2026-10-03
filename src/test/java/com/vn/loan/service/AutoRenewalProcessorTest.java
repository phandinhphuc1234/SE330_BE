package com.vn.loan.service;

import com.vn.loan.dto.response.RenewBorrowResponse;
import com.vn.loan.entity.AutoRenewalAttempt;
import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
import com.vn.loan.enums.AutoRenewalResultCode;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.notification.enums.NotificationTargetType;
import com.vn.notification.enums.NotificationType;
import com.vn.notification.service.NotificationQueueService;
import com.vn.loan.service.impl.policy.CirculationPolicyService;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import com.vn.loan.service.impl.usecase.RenewalUseCase;
import com.vn.loan.service.impl.autorenewal.AutoRenewalAttemptRecorder;
import com.vn.loan.service.impl.autorenewal.AutoRenewalProcessor;
import com.vn.loan.service.impl.autorenewal.AutoRenewalResult;
import com.vn.notification.service.EmailNotificationCommand;
import com.vn.shared.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoRenewalProcessorTest {

    @Mock
    private BorrowRecordRepository borrowRecordRepository;

    @Mock
    private CirculationPolicyService circulationPolicyService;

    @Mock
    private CirculationSettingService circulationSettingService;

    @Mock
    private RenewalUseCase renewalUseCase;

    @Mock
    private AutoRenewalAttemptRecorder attemptRecorder;

    @Mock
    private NotificationQueueService notificationQueueService;

    private AutoRenewalProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AutoRenewalProcessor(
                borrowRecordRepository,
                circulationPolicyService,
                circulationSettingService,
                renewalUseCase,
                attemptRecorder,
                notificationQueueService,
                com.vn.shared.testsupport.TestTime.CLOCK
        );
    }

    @Test
    void processOne_shouldRenewAndRecordSuccess_whenPolicyPasses() {
        BorrowRecord borrow = borrow();
        RenewBorrowResponse renewResponse = new RenewBorrowResponse(
                100L,
                borrow.getDueDate(),
                Instant.parse("2026-05-22T10:00:00Z"),
                1,
                2
        );
        when(borrowRecordRepository.findLockedForRenewalById(100L)).thenReturn(Optional.of(borrow));
        when(circulationPolicyService.validateAutoRenewal(borrow)).thenReturn(AutoRenewalResultCode.SUCCESS);
        when(circulationSettingService.getRenewalDaysDefault()).thenReturn(7);
        when(circulationSettingService.isAutoRenewNotifySuccessEnabled()).thenReturn(true);
        when(renewalUseCase.applyRenewal(borrow, 7)).thenReturn(renewResponse);
        when(attemptRecorder.recordSuccess(
                eq(borrow), eq(900L), any(Instant.class), any(Instant.class),
                any(Instant.class), eq(0), eq(1)))
                .thenReturn(AutoRenewalAttempt.builder().id(301L).build());

        AutoRenewalResult result = processor.processOne(100L, 900L);

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo(AutoRenewalResultCode.SUCCESS);
        verify(attemptRecorder).recordSuccess(
                eq(borrow),
                eq(900L),
                any(Instant.class),
                eq(Instant.parse("2026-05-15T10:00:00Z")),
                eq(Instant.parse("2026-05-22T10:00:00Z")),
                eq(0),
                eq(1)
        );
        ArgumentCaptor<EmailNotificationCommand> commandCaptor =
                ArgumentCaptor.forClass(EmailNotificationCommand.class);
        verify(notificationQueueService).enqueueEmail(commandCaptor.capture());
        assertThat(commandCaptor.getValue().notificationType())
                .isEqualTo(NotificationType.AUTO_RENEWAL_SUCCESS);
        assertThat(commandCaptor.getValue().targetType())
                .isEqualTo(NotificationTargetType.AUTO_RENEWAL_ATTEMPT);
        assertThat(commandCaptor.getValue().targetId()).isEqualTo(301L);
        assertThat(commandCaptor.getValue().eventKey())
                .isEqualTo("AUTO_RENEWAL_SUCCESS:AUTO_RENEWAL_ATTEMPT:301:EMAIL");
    }

    @Test
    void processOne_shouldRecordFailureAndNotify_whenPolicyBlocksRenewal() {
        BorrowRecord borrow = borrow();
        when(borrowRecordRepository.findLockedForRenewalById(100L)).thenReturn(Optional.of(borrow));
        when(circulationPolicyService.validateAutoRenewal(borrow)).thenReturn(AutoRenewalResultCode.BLOCKED_BY_HOLD);
        when(circulationSettingService.isAutoRenewNotifyFailureEnabled()).thenReturn(true);
        when(attemptRecorder.recordFailure(
                eq(borrow), eq(900L), any(Instant.class), eq(AutoRenewalResultCode.BLOCKED_BY_HOLD)))
                .thenReturn(AutoRenewalAttempt.builder().id(302L).build());

        AutoRenewalResult result = processor.processOne(100L, 900L);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(AutoRenewalResultCode.BLOCKED_BY_HOLD);
        verify(attemptRecorder).recordFailure(eq(borrow), eq(900L), any(Instant.class), eq(AutoRenewalResultCode.BLOCKED_BY_HOLD));
        verify(renewalUseCase, never()).applyRenewal(any(), anyInt());
        ArgumentCaptor<EmailNotificationCommand> commandCaptor =
                ArgumentCaptor.forClass(EmailNotificationCommand.class);
        verify(notificationQueueService).enqueueEmail(commandCaptor.capture());
        assertThat(commandCaptor.getValue().notificationType())
                .isEqualTo(NotificationType.AUTO_RENEWAL_FAILURE);
        assertThat(commandCaptor.getValue().targetId()).isEqualTo(302L);
        assertThat(commandCaptor.getValue().payload())
                .containsEntry("reasonCode", "BLOCKED_BY_HOLD");
    }

    @Test
    void processOne_shouldReturnFailed_whenBorrowNotFound() {
        when(borrowRecordRepository.findLockedForRenewalById(404L)).thenReturn(Optional.empty());

        AutoRenewalResult result = processor.processOne(404L, 900L);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(AutoRenewalResultCode.BORROW_NOT_FOUND);
        verify(attemptRecorder, never()).recordFailure(any(), any(), any(), any());
        verify(renewalUseCase, never()).applyRenewal(any(), anyInt());
    }

    private BorrowRecord borrow() {
        Member member = TestDataFactory.activeMember(5L);
        Book book = TestDataFactory.book(10L, 0);
        BookCopy copy = TestDataFactory.bookCopy(50L, book, BookCopyStatus.BORROWED);
        BorrowRecord borrow = TestDataFactory.borrowRecord(100L, member, copy, BorrowStatus.BORROWED);
        borrow.setMaxRenewalsAtCheckout(2);
        return borrow;
    }
}
