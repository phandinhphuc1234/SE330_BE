package com.vn.service.circulation;

import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.enums.AutoRenewalResultCode;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.EmailService;
import com.vn.service.impl.circulation.CirculationPolicyService;
import com.vn.service.impl.circulation.CirculationSettingService;
import com.vn.service.impl.circulation.RenewalUseCase;
import com.vn.service.impl.circulation.autorenewal.AutoRenewalAttemptRecorder;
import com.vn.service.impl.circulation.autorenewal.AutoRenewalProcessor;
import com.vn.service.impl.circulation.autorenewal.AutoRenewalResult;
import com.vn.testsupport.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
    private EmailService emailService;

    private AutoRenewalProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new AutoRenewalProcessor(
                borrowRecordRepository,
                circulationPolicyService,
                circulationSettingService,
                renewalUseCase,
                attemptRecorder,
                emailService
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
        verify(emailService).sendAutoRenewalSuccessEmail(
                eq(5L),
                eq("member5@example.com"),
                eq("Member 5"),
                eq("Clean Code"),
                eq("BC-50"),
                eq(Instant.parse("2026-05-15T10:00:00Z")),
                eq(Instant.parse("2026-05-22T10:00:00Z")),
                eq(1),
                eq(2)
        );
    }

    @Test
    void processOne_shouldRecordFailureAndNotify_whenPolicyBlocksRenewal() {
        BorrowRecord borrow = borrow();
        when(borrowRecordRepository.findLockedForRenewalById(100L)).thenReturn(Optional.of(borrow));
        when(circulationPolicyService.validateAutoRenewal(borrow)).thenReturn(AutoRenewalResultCode.BLOCKED_BY_HOLD);
        when(circulationSettingService.isAutoRenewNotifyFailureEnabled()).thenReturn(true);

        AutoRenewalResult result = processor.processOne(100L, 900L);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo(AutoRenewalResultCode.BLOCKED_BY_HOLD);
        verify(attemptRecorder).recordFailure(eq(borrow), eq(900L), any(Instant.class), eq(AutoRenewalResultCode.BLOCKED_BY_HOLD));
        verify(renewalUseCase, never()).applyRenewal(any(), anyInt());
        verify(emailService).sendAutoRenewalFailureEmail(
                eq(5L),
                eq("member5@example.com"),
                eq("Member 5"),
                eq("Clean Code"),
                eq("BC-50"),
                eq(Instant.parse("2026-05-15T10:00:00Z")),
                eq("BLOCKED_BY_HOLD"),
                eq(AutoRenewalResultCode.BLOCKED_BY_HOLD.defaultMessage())
        );
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
