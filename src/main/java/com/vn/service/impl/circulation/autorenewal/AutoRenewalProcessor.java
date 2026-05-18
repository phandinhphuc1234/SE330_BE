package com.vn.service.impl.circulation.autorenewal;

import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.entity.BorrowRecord;
import com.vn.enums.AutoRenewalResultCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.EmailService;
import com.vn.service.impl.circulation.CirculationPolicyService;
import com.vn.service.impl.circulation.CirculationSettingService;
import com.vn.service.impl.circulation.RenewalUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoRenewalProcessor {

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationPolicyService circulationPolicyService;
    private final CirculationSettingService circulationSettingService;
    private final RenewalUseCase renewalUseCase;
    private final AutoRenewalAttemptRecorder attemptRecorder;
    private final EmailService emailService;

    // Chức năng: xử lý một borrow trong transaction riêng để lỗi một record không làm fail cả job.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoRenewalResult processOne(Long borrowId, Long jobLogId) {
        BorrowRecord borrow = borrowRecordRepository.findLockedForRenewalById(borrowId).orElse(null);
        if (borrow == null) {
            log.warn("eventType={} result={} borrowId={} reasonCode={}",
                    LogEvent.AUTO_RENEWAL_ATTEMPT, LogResult.FAILED, borrowId, AutoRenewalResultCode.BORROW_NOT_FOUND);
            return AutoRenewalResult.failed(AutoRenewalResultCode.BORROW_NOT_FOUND);
        }

        try {
            return processBorrow(borrow, jobLogId);
        } catch (Exception e) {
            attemptRecorder.recordFailure(borrow, jobLogId, Instant.now(), AutoRenewalResultCode.SYSTEM_ERROR);
            log.error("eventType={} result={} borrowId={} memberId={} reasonCode={}",
                    LogEvent.AUTO_RENEWAL_ATTEMPT,
                    LogResult.FAILED,
                    borrow.getId(),
                    borrow.getMember().getId(),
                    AutoRenewalResultCode.SYSTEM_ERROR,
                    e);
            return AutoRenewalResult.failed(AutoRenewalResultCode.SYSTEM_ERROR);
        }
    }

    private AutoRenewalResult processBorrow(BorrowRecord borrow, Long jobLogId) {
        Instant attemptedAt = Instant.now();
        AutoRenewalResultCode validationResult = circulationPolicyService.validateAutoRenewal(borrow);
        if (validationResult != AutoRenewalResultCode.SUCCESS) {
            attemptRecorder.recordFailure(borrow, jobLogId, attemptedAt, validationResult);
            sendFailureEmailIfEnabled(borrow, validationResult);
            log.warn("eventType={} result={} borrowId={} memberId={} reasonCode={}",
                    LogEvent.AUTO_RENEWAL_ATTEMPT, LogResult.FAILED, borrow.getId(), borrow.getMember().getId(), validationResult);
            return AutoRenewalResult.failed(validationResult);
        }

        Instant oldDueDate = borrow.getDueDate();
        int renewCountBefore = borrow.getRenewCount();
        RenewBorrowResponse renewed = renewalUseCase.applyRenewal(
                borrow,
                circulationSettingService.getRenewalDaysDefault()
        );

        attemptRecorder.recordSuccess(
                borrow,
                jobLogId,
                attemptedAt,
                oldDueDate,
                renewed.newDueDate(),
                renewCountBefore,
                renewed.renewCount()
        );
        sendSuccessEmailIfEnabled(borrow, oldDueDate, renewed);

        log.info("eventType={} result={} borrowId={} memberId={} oldDueDate={} newDueDate={} renewCount={}",
                LogEvent.AUTO_RENEWAL_ATTEMPT,
                LogResult.SUCCESS,
                borrow.getId(),
                borrow.getMember().getId(),
                oldDueDate,
                renewed.newDueDate(),
                renewed.renewCount());

        return AutoRenewalResult.succeeded();
    }

    private void sendSuccessEmailIfEnabled(BorrowRecord borrow, Instant oldDueDate, RenewBorrowResponse renewed) {
        if (!circulationSettingService.isAutoRenewNotifySuccessEnabled()) {
            return;
        }
        emailService.sendAutoRenewalSuccessEmail(
                borrow.getMember().getId(),
                borrow.getMember().getEmail(),
                borrow.getMember().getFullName(),
                borrow.getBookCopy().getBook().getTitle(),
                borrow.getBookCopy().getBarcode(),
                oldDueDate,
                renewed.newDueDate(),
                renewed.renewCount(),
                renewed.maxRenewals()
        );
    }

    private void sendFailureEmailIfEnabled(BorrowRecord borrow, AutoRenewalResultCode code) {
        if (!circulationSettingService.isAutoRenewNotifyFailureEnabled()) {
            return;
        }
        emailService.sendAutoRenewalFailureEmail(
                borrow.getMember().getId(),
                borrow.getMember().getEmail(),
                borrow.getMember().getFullName(),
                borrow.getBookCopy().getBook().getTitle(),
                borrow.getBookCopy().getBarcode(),
                borrow.getDueDate(),
                code.name(),
                code.defaultMessage()
        );
    }
}
