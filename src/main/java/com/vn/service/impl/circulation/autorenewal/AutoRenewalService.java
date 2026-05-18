package com.vn.service.impl.circulation.autorenewal;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.impl.circulation.CirculationSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class AutoRenewalService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationSettingService circulationSettingService;
    private final AutoRenewalProcessor autoRenewalProcessor;

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
            AutoRenewalResult result = autoRenewalProcessor.processOne(borrow.getId(), jobLogId);
            if (result.success()) {
                successCount++;
            } else {
                failedCount++;
            }
        }

        return new AutoRenewalJobSummary(candidates.getNumberOfElements(), successCount, failedCount);
    }

    // Chức năng: tính cửa sổ ngày nghiệp vụ để tránh quét lặp record khi job chạy nhiều lần trong ngày.
    AutoRenewalWindow buildWindow() {
        LocalDate targetDate = LocalDate.now(BUSINESS_ZONE)
                .plusDays(circulationSettingService.getAutoRenewDaysBeforeDue());
        return new AutoRenewalWindow(
                targetDate.atStartOfDay(BUSINESS_ZONE).toInstant(),
                targetDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()
        );
    }
}
