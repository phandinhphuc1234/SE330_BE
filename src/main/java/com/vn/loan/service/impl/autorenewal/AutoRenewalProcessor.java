package com.vn.loan.service.impl.autorenewal;

import com.vn.loan.dto.response.RenewBorrowResponse;
import com.vn.loan.entity.AutoRenewalAttempt;
import com.vn.loan.entity.BorrowRecord;
import com.vn.loan.enums.AutoRenewalResultCode;
import com.vn.notification.enums.NotificationTargetType;
import com.vn.notification.enums.NotificationType;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.notification.service.NotificationQueueService;
import com.vn.notification.service.EmailNotificationCommand;
import com.vn.loan.service.impl.policy.CirculationPolicyService;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import com.vn.loan.service.impl.usecase.RenewalUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoRenewalProcessor {

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationPolicyService circulationPolicyService;
    private final CirculationSettingService circulationSettingService;
    private final RenewalUseCase renewalUseCase;
    private final AutoRenewalAttemptRecorder attemptRecorder;
    private final NotificationQueueService notificationQueueService;
    private final Clock clock;

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
        } catch (RuntimeException e) {
            log.error("eventType={} result={} borrowId={} memberId={} reasonCode={}",
                    LogEvent.AUTO_RENEWAL_ATTEMPT,
                    LogResult.FAILED,
                    borrow.getId(),
                    borrow.getMember().getId(),
                    AutoRenewalResultCode.SYSTEM_ERROR,
                    e);
            // Leave the transactional proxy with an exception so the renewal,
            // attempt and delivery request roll back atomically. The outer batch
            // catches the error and continues with the next record.
            throw e;
        }
    }

    private AutoRenewalResult processBorrow(BorrowRecord borrow, Long jobLogId) {
        Instant attemptedAt = clock.instant();
        AutoRenewalResultCode validationResult = circulationPolicyService.validateAutoRenewal(borrow);
        if (validationResult != AutoRenewalResultCode.SUCCESS) {
            AutoRenewalAttempt attempt = attemptRecorder.recordFailure(
                    borrow, jobLogId, attemptedAt, validationResult);
            enqueueFailureNotificationIfEnabled(borrow, attempt, validationResult);
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

        AutoRenewalAttempt attempt = attemptRecorder.recordSuccess(
                borrow,
                jobLogId,
                attemptedAt,
                oldDueDate,
                renewed.newDueDate(),
                renewCountBefore,
                renewed.renewCount()
        );
        enqueueSuccessNotificationIfEnabled(borrow, attempt, oldDueDate, renewed);

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

    private void enqueueSuccessNotificationIfEnabled(BorrowRecord borrow,
                                                       AutoRenewalAttempt attempt,
                                                       Instant oldDueDate,
                                                       RenewBorrowResponse renewed) {
        if (!circulationSettingService.isAutoRenewNotifySuccessEnabled()) {
            return;
        }
        notificationQueueService.enqueueEmail(EmailNotificationCommand.builder()
                .member(borrow.getMember())
                .title("Gia hạn sách tự động thành công")
                .content("Sách \"" + borrow.getBookCopy().getBook().getTitle()
                        + "\" đã được gia hạn tự động.")
                .notificationType(NotificationType.AUTO_RENEWAL_SUCCESS)
                .targetType(NotificationTargetType.AUTO_RENEWAL_ATTEMPT)
                .targetId(attempt.getId())
                .eventKey("AUTO_RENEWAL_SUCCESS:AUTO_RENEWAL_ATTEMPT:" + attempt.getId() + ":EMAIL")
                .templateCode("auto-renewal-success")
                .payload(Map.of(
                        "fullName", displayName(borrow),
                        "bookTitle", borrow.getBookCopy().getBook().getTitle(),
                        "barcode", borrow.getBookCopy().getBarcode(),
                        "oldDueDate", oldDueDate.toString(),
                        "newDueDate", renewed.newDueDate().toString(),
                        "renewCount", renewed.renewCount(),
                        "maxRenewals", renewed.maxRenewals()
                ))
                .build());
    }

    private void enqueueFailureNotificationIfEnabled(BorrowRecord borrow,
                                                       AutoRenewalAttempt attempt,
                                                       AutoRenewalResultCode code) {
        if (!circulationSettingService.isAutoRenewNotifyFailureEnabled()) {
            return;
        }
        notificationQueueService.enqueueEmail(EmailNotificationCommand.builder()
                .member(borrow.getMember())
                .title("Không thể tự động gia hạn sách")
                .content("Sách \"" + borrow.getBookCopy().getBook().getTitle()
                        + "\" không thể được gia hạn tự động.")
                .notificationType(NotificationType.AUTO_RENEWAL_FAILURE)
                .targetType(NotificationTargetType.AUTO_RENEWAL_ATTEMPT)
                .targetId(attempt.getId())
                .eventKey("AUTO_RENEWAL_FAILURE:AUTO_RENEWAL_ATTEMPT:" + attempt.getId() + ":EMAIL")
                .templateCode("auto-renewal-failure")
                .payload(Map.of(
                        "fullName", displayName(borrow),
                        "bookTitle", borrow.getBookCopy().getBook().getTitle(),
                        "barcode", borrow.getBookCopy().getBarcode(),
                        "dueDate", borrow.getDueDate().toString(),
                        "reasonCode", code.name(),
                        "reasonMessage", code.defaultMessage()
                ))
                .build());
    }

    private String displayName(BorrowRecord borrow) {
        String fullName = borrow.getMember().getFullName();
        return fullName == null || fullName.isBlank() ? "Bạn đọc" : fullName.strip();
    }
}
