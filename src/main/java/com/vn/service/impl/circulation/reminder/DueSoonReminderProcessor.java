package com.vn.service.impl.circulation.reminder;

import com.vn.entity.Book;
import com.vn.entity.BookCopy;
import com.vn.entity.BorrowRecord;
import com.vn.entity.Member;
import com.vn.entity.Notification;
import com.vn.entity.NotificationQueue;
import com.vn.enums.BorrowStatus;
import com.vn.enums.NotificationChannel;
import com.vn.enums.NotificationQueueStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.BorrowRecordRepository;
import com.vn.repository.NotificationQueueRepository;
import com.vn.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DueSoonReminderProcessor {

    private final BorrowRecordRepository borrowRecordRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationQueueRepository notificationQueueRepository;

    // Chức năng: tạo in-app notification và email queue cho một lượt mượn sắp đến hạn nếu chưa từng tạo.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DueSoonReminderResult createReminderIfNeeded(Long borrowId, DueSoonReminderWindow window) {
        BorrowRecord borrow = borrowRecordRepository.findById(borrowId).orElse(null);
        if (borrow == null || !isStillDueSoonBorrow(borrow, window)) {
            return DueSoonReminderResult.skipped();
        }
        if (isReminderAlreadyCreated(borrow.getId())) {
            return DueSoonReminderResult.skipped();
        }

        Member member = borrow.getMember();
        BookCopy copy = borrow.getBookCopy();
        Book book = copy.getBook();

        Notification notification = notificationRepository.save(Notification.builder()
                .member(member)
                .title("Sách sắp đến hạn trả")
                .content("Sách \"" + book.getTitle() + "\" sắp đến hạn trả.")
                .type(NotificationType.DUE_SOON_REMINDER)
                .build());

        notificationQueueRepository.save(NotificationQueue.builder()
                .member(member)
                .notification(notification)
                .channel(NotificationChannel.EMAIL)
                .status(NotificationQueueStatus.PENDING)
                .notificationType(NotificationType.DUE_SOON_REMINDER)
                .targetType(NotificationTargetType.BORROW_RECORD)
                .targetId(borrow.getId())
                .build());

        log.info("eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} bookCopyId={}",
                LogEvent.CREATE_DUE_SOON_REMINDER,
                LogResult.SUCCESS,
                member.getId(),
                borrow.getId(),
                copy.getId());

        return DueSoonReminderResult.created(
                member.getId(),
                member.getEmail(),
                member.getFullName(),
                book.getTitle(),
                copy.getBarcode(),
                borrow.getDueDate()
        );
    }

    private boolean isStillDueSoonBorrow(BorrowRecord borrow, DueSoonReminderWindow window) {
        return borrow.getStatus() == BorrowStatus.BORROWED
                && !borrow.getDueDate().isBefore(window.start())
                && borrow.getDueDate().isBefore(window.end());
    }

    private boolean isReminderAlreadyCreated(Long borrowId) {
        return notificationQueueRepository.existsByNotificationTypeAndTargetTypeAndTargetIdAndChannel(
                NotificationType.DUE_SOON_REMINDER,
                NotificationTargetType.BORROW_RECORD,
                borrowId,
                NotificationChannel.EMAIL
        );
    }
}
