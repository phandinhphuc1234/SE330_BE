package com.vn.service.impl.circulation;

import com.vn.dto.circulation.request.RenewBorrowRequest;
import com.vn.dto.circulation.response.RenewBorrowResponse;
import com.vn.entity.BorrowRecord;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.BorrowRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalUseCase {

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationPolicyService circulationPolicyService;
    private final CirculationSettingService circulationSettingService;

    // Chức năng: xử lý gia hạn chung cho cả member tự gia hạn và staff gia hạn hộ.
    public RenewBorrowResponse renew(Long actorId, boolean staffFlow, Long borrowId, RenewBorrowRequest request) {
        BorrowRecord borrow = borrowRecordRepository.findById(borrowId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        circulationPolicyService.assertRenewalAllowed(actorId, staffFlow, borrow);

        int requestedDays = request.requestedDays() == null
                ? circulationSettingService.getRenewalDaysDefault()
                : request.requestedDays();
        Instant oldDueDate = borrow.getDueDate();
        Instant newDueDate = oldDueDate.plus(requestedDays, ChronoUnit.DAYS);
        borrow.setDueDate(newDueDate);
        borrow.setRenewCount(borrow.getRenewCount() + 1);
        borrowRecordRepository.save(borrow);

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} renewCount={}",
                LogEvent.RENEW_BORROW, LogResult.SUCCESS, borrow.getMember().getId(), borrow.getId(), borrow.getRenewCount());

        return new RenewBorrowResponse(
                borrow.getId(),
                oldDueDate,
                newDueDate,
                borrow.getRenewCount(),
                borrow.getMaxRenewalsAtCheckout()
        );
    }
}
