package com.vn.service.impl.circulation.reminder;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import com.vn.service.EmailService;
import com.vn.service.impl.circulation.policy.CirculationSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class DueSoonReminderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final BorrowRecordRepository borrowRecordRepository;
    private final CirculationSettingService circulationSettingService;
    private final DueSoonReminderProcessor dueSoonReminderProcessor;
    private final EmailService emailService;

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
                emailService.sendDueSoonReminderEmail(
                        result.memberId(),
                        result.toEmail(),
                        result.fullName(),
                        result.bookTitle(),
                        result.barcode(),
                        result.dueDate()
                );
            } else {
                failedCount++;
            }
        }

        return new DueSoonReminderJobSummary(candidates.getNumberOfElements(), successCount, failedCount);
    }

    // Chức năng: tính ngày nghiệp vụ cần nhắc để job chạy nhiều lần trong ngày vẫn cùng một window.
    DueSoonReminderWindow buildWindow() {
        LocalDate targetDate = LocalDate.now(BUSINESS_ZONE)
                .plusDays(circulationSettingService.getDueSoonReminderDaysBeforeDue());
        return new DueSoonReminderWindow(
                targetDate.atStartOfDay(BUSINESS_ZONE).toInstant(),
                targetDate.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()
        );
    }
}
