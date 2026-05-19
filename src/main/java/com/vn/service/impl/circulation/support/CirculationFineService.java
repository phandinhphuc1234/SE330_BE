package com.vn.service.impl.circulation.support;

import com.vn.entity.BorrowRecord;
import com.vn.entity.FineConfig;
import com.vn.repository.FineConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class CirculationFineService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final FineConfigRepository fineConfigRepository;

    // Chức năng: tính số ngày quá hạn dựa trên ngày đến hạn và ngày trả thực tế.
    public long calculateOverdueDays(Instant dueDate, Instant returnedAt) {
        LocalDate due = dueDate.atZone(BUSINESS_ZONE).toLocalDate();
        LocalDate returned = returnedAt.atZone(BUSINESS_ZONE).toLocalDate();
        return Math.max(0, ChronoUnit.DAYS.between(due, returned));
    }

    // Chức năng: áp dụng cấu hình phạt hiện hành để tính tiền phạt cho lượt mượn quá hạn.
    public void applyOverdueFine(BorrowRecord borrow, long overdueDays, Instant calculatedAt) {
        LocalDate today = calculatedAt.atZone(BUSINESS_ZONE).toLocalDate();
        FineConfig config = fineConfigRepository.findActiveConfig(today).orElse(null);
        if (config == null) {
            return;
        }
        // Lưu lại config đã dùng để lịch sử phạt không bị đổi khi cấu hình tiền phạt thay đổi sau này.
        borrow.setFineConfig(config);
        borrow.setFineAmount(config.getRatePerDay().multiply(BigDecimal.valueOf(overdueDays)));
        borrow.setFineCalculatedAt(calculatedAt);
    }
}
