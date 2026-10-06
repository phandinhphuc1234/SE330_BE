package com.vn.loan.service.impl.support;

import com.vn.loan.entity.BorrowRecord;
import com.vn.loan.entity.FineConfig;
import com.vn.loan.repository.FineConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class CirculationFineService {

    private final FineConfigRepository fineConfigRepository;
    private final Clock clock;

    // Chức năng: tính số ngày quá hạn dựa trên ngày đến hạn và ngày trả thực tế.
    public long calculateOverdueDays(Instant dueDate, Instant returnedAt) {
        LocalDate due = dueDate.atZone(clock.getZone()).toLocalDate();
        LocalDate returned = returnedAt.atZone(clock.getZone()).toLocalDate();
        return Math.max(0, ChronoUnit.DAYS.between(due, returned));
    }

    // Chức năng: áp dụng cấu hình phạt hiện hành để tính tiền phạt cho lượt mượn quá hạn.
    public void applyOverdueFine(BorrowRecord borrow, long overdueDays, Instant calculatedAt) {
        LocalDate today = calculatedAt.atZone(clock.getZone()).toLocalDate();
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
