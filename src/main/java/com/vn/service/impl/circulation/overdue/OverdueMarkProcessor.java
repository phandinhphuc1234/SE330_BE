package com.vn.service.impl.circulation.overdue;

import com.vn.entity.BorrowRecord;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.BorrowStatus;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.BorrowRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverdueMarkProcessor {

    private final BorrowRecordRepository borrowRecordRepository;

    // Chức năng: xử lý một lượt mượn quá hạn trong transaction riêng để lỗi một record không làm fail cả job.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OverdueMarkResult markOne(Long borrowId) {
        BorrowRecord borrow = borrowRecordRepository.findLockedForOverdueById(borrowId).orElse(null);
        if (borrow == null || borrow.getStatus() != BorrowStatus.BORROWED) {
            return OverdueMarkResult.skipped();
        }

        borrow.setStatus(BorrowStatus.OVERDUE);
        borrow.getBookCopy().setStatus(BookCopyStatus.OVERDUE);
        borrowRecordRepository.save(borrow);

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} bookCopyId={}",
                LogEvent.MARK_BORROW_OVERDUE,
                LogResult.SUCCESS,
                borrow.getMember().getId(),
                borrow.getId(),
                borrow.getBookCopy().getId());

        return OverdueMarkResult.succeeded();
    }
}
