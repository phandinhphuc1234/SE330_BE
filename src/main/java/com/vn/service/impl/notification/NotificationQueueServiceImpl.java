package com.vn.service.impl.notification;

import com.vn.entity.Member;
import com.vn.entity.Notification;
import com.vn.entity.NotificationQueue;
import com.vn.enums.NotificationChannel;
import com.vn.enums.NotificationQueueStatus;
import com.vn.repository.NotificationQueueRepository;
import com.vn.repository.NotificationRepository;
import com.vn.service.NotificationQueueService;
import com.vn.service.notification.EmailNotificationCommand;
import com.vn.service.notification.NotificationEnqueueResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
public class NotificationQueueServiceImpl implements NotificationQueueService {

    private final NotificationRepository notificationRepository;
    private final NotificationQueueRepository notificationQueueRepository;
    private final NotificationEventKeyLock eventKeyLock;
    private final NotificationPayloadGuard payloadGuard;
    private final Clock clock;

    // Must join the transaction that performs the business action. This gives
    // the system one atomic commit for the domain change, in-app notification,
    // and durable email delivery request.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public NotificationEnqueueResult enqueueEmail(EmailNotificationCommand command) {
        validateRecipient(command.member());
        payloadGuard.validate(command.payload());

        eventKeyLock.acquire(command.eventKey());
        return notificationQueueRepository.findByEventKey(command.eventKey())
                .map(this::existingResult)
                .orElseGet(() -> createDelivery(command));
    }

    private NotificationEnqueueResult createDelivery(EmailNotificationCommand command) {
        Instant scheduledAt = command.scheduledAt() == null ? clock.instant() : command.scheduledAt();
        Notification notification = notificationRepository.save(Notification.builder()
                .member(command.member())
                .title(command.title())
                .content(command.content())
                .type(command.notificationType())
                .build());

        NotificationQueue queue = notificationQueueRepository.save(NotificationQueue.builder()
                .member(command.member())
                .notification(notification)
                .channel(NotificationChannel.EMAIL)
                .status(NotificationQueueStatus.PENDING)
                .retryCount(0)
                .maxAttempts(command.maxAttempts())
                .scheduledAt(scheduledAt)
                .nextAttemptAt(scheduledAt)
                .notificationType(command.notificationType())
                .targetType(command.targetType())
                .targetId(command.targetId())
                .eventKey(command.eventKey())
                .recipientEmail(command.member().getEmail().strip())
                .templateCode(command.templateCode())
                .payload(new LinkedHashMap<>(command.payload()))
                .build());

        return new NotificationEnqueueResult(notification.getId(), queue.getId(), true);
    }

    private NotificationEnqueueResult existingResult(NotificationQueue queue) {
        Long notificationId = queue.getNotification() == null ? null : queue.getNotification().getId();
        return new NotificationEnqueueResult(notificationId, queue.getId(), false);
    }

    private void validateRecipient(Member member) {
        if (member.getEmail() == null || member.getEmail().isBlank()) {
            throw new IllegalArgumentException("member email is required for EMAIL notification");
        }
    }
}
