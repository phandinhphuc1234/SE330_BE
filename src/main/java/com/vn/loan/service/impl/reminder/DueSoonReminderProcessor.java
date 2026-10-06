package com.vn.loan.service.impl.reminder;

import com.vn.book.entity.Book;
import com.vn.book.entity.BookCopy;
import com.vn.loan.entity.BorrowRecord;
import com.vn.member.entity.Member;
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
public class DueSoonReminderProcessor {

    private final BorrowRecordRepository borrowRecordRepository;
    private final NotificationQueueService notificationQueueService;

    // Chức năng: tạo in-app notification và email queue cho một lượt mượn sắp đến hạn nếu chưa từng tạo.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DueSoonReminderResult createReminderIfNeeded(Long borrowId, DueSoonReminderWindow window) {
        BorrowRecord borrow = borrowRecordRepository.findById(borrowId).orElse(null);
        if (borrow == null || !isStillDueSoonBorrow(borrow, window)) {
            return DueSoonReminderResult.skipped();
        }
        Member member = borrow.getMember();
        BookCopy copy = borrow.getBookCopy();
        Book book = copy.getBook();
        String title = "Sách sắp đến hạn trả";
        String content = "Sách \"" + book.getTitle() + "\" sắp đến hạn trả.";

        boolean created = notificationQueueService.enqueueEmail(EmailNotificationCommand.builder()
                .member(member)
                .title(title)
                .content(content)
                .notificationType(NotificationType.DUE_SOON_REMINDER)
                .targetType(NotificationTargetType.BORROW_RECORD)
                .targetId(borrow.getId())
                .eventKey("DUE_SOON_REMINDER:BORROW_RECORD:" + borrow.getId() + ":EMAIL")
                .templateCode("due-soon-reminder")
                .payload(Map.of(
                        "fullName", displayName(member),
                        "bookTitle", book.getTitle(),
                        "barcode", copy.getBarcode(),
                        "dueDate", borrow.getDueDate().toString()
                ))
                .build()).created();

        if (!created) {
            return DueSoonReminderResult.skipped();
        }

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} bookCopyId={}",
                LogEvent.CREATE_DUE_SOON_REMINDER,
                LogResult.SUCCESS,
                member.getId(),
                borrow.getId(),
                copy.getId());

        return DueSoonReminderResult.enqueued();
    }

    private boolean isStillDueSoonBorrow(BorrowRecord borrow, DueSoonReminderWindow window) {
        return borrow.getStatus() == BorrowStatus.BORROWED
                && !borrow.getDueDate().isBefore(window.start())
                && borrow.getDueDate().isBefore(window.end());
    }

    private String displayName(Member member) {
        return member.getFullName() == null || member.getFullName().isBlank()
                ? "Bạn đọc"
                : member.getFullName().strip();
    }
}
