package com.vn.service.impl.circulation.autorenewal;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoRenewalService {

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationSettingService circulationSettingService;
    private final AutoRenewalProcessor autoRenewalProcessor;
    private final Clock clock;

    // Chức năng: quét các lượt mượn sắp đến hạn và xử lý auto-renew từng record.
    public AutoRenewalJobSummary runDailyAutoRenewal(Long jobLogId) {
        AutoRenewalWindow window = buildWindow();
        Page<BorrowRecord> candidates = borrowRecordRepository.findAutoRenewalCandidates(
                BorrowStatus.BORROWED,
                window.start(),
                window.end(),
                PageRequest.of(0, circulationSettingService.getAutoRenewMaxItemsPerRun())
        );

        int successCount = 0;
        int failedCount = 0;
        for (BorrowRecord borrow : candidates.getContent()) {
            try {
                AutoRenewalResult result = autoRenewalProcessor.processOne(borrow.getId(), jobLogId);
                if (result.success()) {
                    successCount++;
                } else {
                    failedCount++;
                }
            } catch (RuntimeException e) {
                failedCount++;
                log.error("Auto-renewal failed for borrowId={}; continuing the batch", borrow.getId(), e);
            }
        }

        return new AutoRenewalJobSummary(candidates.getNumberOfElements(), successCount, failedCount);
    }

    // Chức năng: tính cửa sổ ngày nghiệp vụ để tránh quét lặp record khi job chạy nhiều lần trong ngày.
    AutoRenewalWindow buildWindow() {
        LocalDate targetDate = LocalDate.now(clock)
                .plusDays(circulationSettingService.getAutoRenewDaysBeforeDue());
        return new AutoRenewalWindow(
                targetDate.atStartOfDay(clock.getZone()).toInstant(),
                targetDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant()
        );
    }
}
