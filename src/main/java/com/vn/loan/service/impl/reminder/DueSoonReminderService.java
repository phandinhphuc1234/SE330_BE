package com.vn.loan.service.impl.reminder;

import com.vn.loan.entity.BorrowRecord;
import com.vn.loan.enums.BorrowStatus;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.loan.service.impl.policy.CirculationSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class DueSoonReminderService {

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationSettingService circulationSettingService;
    private final DueSoonReminderProcessor dueSoonReminderProcessor;
    private final Clock clock;

    // Chức năng: quét các lượt mượn sắp đến hạn, tạo notification và gửi email nhắc trả.
    public DueSoonReminderJobSummary sendDueSoonReminders() {
        DueSoonReminderWindow window = buildWindow();
        Page<BorrowRecord> candidates = borrowRecordRepository.findDueSoonReminderCandidates(
                BorrowStatus.BORROWED,
                window.start(),
                window.end(),
                PageRequest.of(0, circulationSettingService.getDueSoonReminderMaxItemsPerRun())
        );

        int successCount = 0;
        int failedCount = 0;
        for (BorrowRecord borrow : candidates.getContent()) {
            DueSoonReminderResult result = dueSoonReminderProcessor.createReminderIfNeeded(borrow.getId(), window);
            if (result.created()) {
                successCount++;
            } else {
                failedCount++;
            }
        }

        return new DueSoonReminderJobSummary(candidates.getNumberOfElements(), successCount, failedCount);
    }

    // Chức năng: tính ngày nghiệp vụ cần nhắc để job chạy nhiều lần trong ngày vẫn cùng một window.
    DueSoonReminderWindow buildWindow() {
        LocalDate targetDate = LocalDate.now(clock)
                .plusDays(circulationSettingService.getDueSoonReminderDaysBeforeDue());
        return new DueSoonReminderWindow(
                targetDate.atStartOfDay(clock.getZone()).toInstant(),
                targetDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant()
        );
    }
}
