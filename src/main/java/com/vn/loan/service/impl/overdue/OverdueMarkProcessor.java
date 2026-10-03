package com.vn.loan.service.impl.overdue;

import com.vn.loan.entity.BorrowRecord;
import com.vn.book.enums.BookCopyStatus;
import com.vn.loan.enums.BorrowStatus;
import com.vn.notification.enums.NotificationTargetType;
import com.vn.notification.enums.NotificationType;
import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.loan.repository.BorrowRecordRepository;
import com.vn.notification.service.NotificationQueueService;
import com.vn.notification.service.EmailNotificationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverdueMarkProcessor {

    private final BorrowRecordRepository borrowRecordRepository;
    private final NotificationQueueService notificationQueueService;

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

        notificationQueueService.enqueueEmail(EmailNotificationCommand.builder()
                .member(borrow.getMember())
                .title("Sách đã quá hạn")
                .content("Sách \"" + borrow.getBookCopy().getBook().getTitle() + "\" đã quá hạn trả.")
                .notificationType(NotificationType.BORROW_OVERDUE)
                .targetType(NotificationTargetType.BORROW_RECORD)
                .targetId(borrow.getId())
                .eventKey("BORROW_OVERDUE:BORROW_RECORD:" + borrow.getId() + ":EMAIL")
                .templateCode("borrow-overdue")
                .payload(Map.of(
                        "fullName", displayName(borrow),
                        "bookTitle", borrow.getBookCopy().getBook().getTitle(),
                        "barcode", borrow.getBookCopy().getBarcode(),
                        "dueDate", borrow.getDueDate().toString()
                ))
                .build());

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} bookCopyId={}",
                LogEvent.MARK_BORROW_OVERDUE,
                LogResult.SUCCESS,
                borrow.getMember().getId(),
                borrow.getId(),
                borrow.getBookCopy().getId());

        return OverdueMarkResult.succeeded();
    }

    private String displayName(BorrowRecord borrow) {
        String fullName = borrow.getMember().getFullName();
        return fullName == null || fullName.isBlank() ? "Bạn đọc" : fullName.strip();
    }
}
