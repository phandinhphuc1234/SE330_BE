package com.vn.service.impl.circulation.overdue;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BorrowStatus;
import com.vn.repository.BorrowRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OverdueMarkService {

    private static final int MAX_ITEMS_PER_RUN = 500;

    private final BorrowRecordRepository borrowRecordRepository;
    private final OverdueMarkProcessor overdueMarkProcessor;

    // Chức năng: tìm các borrow đã quá hạn và chuyển từng record sang trạng thái OVERDUE.
    public OverdueJobSummary markOverdueBorrows() {
        Page<BorrowRecord> candidates = borrowRecordRepository.findOverdueCandidates(
                BorrowStatus.BORROWED,
                Instant.now(),
                PageRequest.of(0, MAX_ITEMS_PER_RUN)
        );

        int successCount = 0;
        int failedCount = 0;
        for (BorrowRecord borrow : candidates.getContent()) {
            OverdueMarkResult result = overdueMarkProcessor.markOne(borrow.getId());
            if (result.success()) {
                successCount++;
            } else {
                failedCount++;
            }
        }

        return new OverdueJobSummary(candidates.getNumberOfElements(), successCount, failedCount);
    }
}
