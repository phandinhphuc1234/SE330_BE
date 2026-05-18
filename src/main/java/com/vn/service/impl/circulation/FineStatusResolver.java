package com.vn.service.impl.circulation;

import com.vn.entity.BorrowRecord;
import com.vn.enums.FineStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class FineStatusResolver {

    // Chức năng: diễn giải trạng thái tiền phạt từ các cột fine đang lưu trực tiếp trên borrow_records.
    public FineStatus resolve(BorrowRecord borrow) {
        if (safeFineAmount(borrow).compareTo(BigDecimal.ZERO) <= 0) {
            return FineStatus.NONE;
        }
        if (borrow.getFineWaivedBy() != null) {
            return FineStatus.WAIVED;
        }
        if (borrow.getFinePaidAt() != null) {
            return FineStatus.PAID;
        }
        return FineStatus.UNPAID;
    }

    private BigDecimal safeFineAmount(BorrowRecord borrow) {
        return borrow.getFineAmount() == null ? BigDecimal.ZERO : borrow.getFineAmount();
    }
}
