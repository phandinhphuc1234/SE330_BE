package com.vn.service.impl;

import com.vn.dto.notification.response.NotificationDeliveryResponse;
import com.vn.dto.notification.response.NotificationDeliverySummaryResponse;
import com.vn.entity.NotificationQueue;
import com.vn.enums.NotificationQueueStatus;
import com.vn.enums.NotificationType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.NotificationDeliveryAuditRepository;
import com.vn.repository.NotificationQueueRepository;
import com.vn.service.NotificationDeliveryAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryAdminServiceImpl implements NotificationDeliveryAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationQueueRepository queueRepository;
    private final NotificationDeliveryAuditRepository auditRepository;
    private final Clock notificationDeliveryClock;

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationDeliveryResponse> search(
            String query,
            String status,
            String notificationType,
            int page,
            int size
    ) {
        validatePage(page, size);
        NotificationQueueStatus parsedStatus = parseStatus(status);
        NotificationType parsedType = parseType(notificationType);

        Specification<NotificationQueue> specification = (root, criteriaQuery, builder) -> builder.conjunction();
        if (StringUtils.hasText(query)) {
            String pattern = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, criteriaQuery, builder) -> builder.or(
                    builder.like(builder.lower(root.get("eventKey")), pattern),
                    builder.like(builder.lower(root.get("recipientEmail")), pattern),
                    builder.like(builder.lower(root.get("providerMessageId")), pattern)
            ));
        }
        if (parsedStatus != null) {
            specification = specification.and(
                    (root, criteriaQuery, builder) -> builder.equal(root.get("status"), parsedStatus)
            );
        }
        if (parsedType != null) {
            specification = specification.and(
                    (root, criteriaQuery, builder) -> builder.equal(root.get("notificationType"), parsedType)
            );
        }

        return queueRepository.findAll(
                specification,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationDeliverySummaryResponse getSummary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (NotificationQueueStatus status : NotificationQueueStatus.values()) {
            counts.put(status.name(), 0L);
        }
        for (Object[] row : queueRepository.countGroupedByStatus()) {
            NotificationQueueStatus status = (NotificationQueueStatus) row[0];
            counts.put(status.name(), (Long) row[1]);
        }

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long awaitingDelivery = count(counts, NotificationQueueStatus.PENDING)
                + count(counts, NotificationQueueStatus.PROCESSING)
                + count(counts, NotificationQueueStatus.RETRY)
                + count(counts, NotificationQueueStatus.SENT);
        long needsAttention = count(counts, NotificationQueueStatus.DEAD)
                + count(counts, NotificationQueueStatus.FAILED)
                + count(counts, NotificationQueueStatus.BOUNCED)
                + count(counts, NotificationQueueStatus.COMPLAINED);
        return new NotificationDeliverySummaryResponse(total, awaitingDelivery, needsAttention, Map.copyOf(counts));
    }

    @Override
    @Transactional
    public NotificationDeliveryResponse retryDeadDelivery(Long adminId, Long queueId) {
        NotificationQueue queue = queueRepository.findByIdForUpdate(queueId)
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_DELIVERY_NOT_FOUND));
        if (queue.getStatus() != NotificationQueueStatus.DEAD) {
            throw new AppException(ErrorCode.NOTIFICATION_DELIVERY_NOT_RETRYABLE);
        }

        int previousAttempt = safeAttempt(queue);
        int newAttempt = Math.addExact(previousAttempt, 1);
        String previousError = queue.getLastError();
        Instant now = notificationDeliveryClock.instant();

        queue.setStatus(NotificationQueueStatus.PENDING);
        queue.setDeliveryAttempt(newAttempt);
        queue.setProviderRequestKey("notification-" + queue.getId() + "-delivery-" + newAttempt);
        queue.setProviderMessageId(null);
        queue.setRetryCount(0);
        queue.setNextAttemptAt(now);
        queue.setLastAttemptAt(null);
        queue.setLockedAt(null);
        queue.setLockedBy(null);
        queue.setSentAt(null);
        queue.setDeliveredAt(null);
        queue.setLastError(null);
        queueRepository.save(queue);

        auditRepository.recordManualRetry(
                adminId,
                queue.getId(),
                previousAttempt,
                newAttempt,
                previousError
        );
        log.info(
                "eventType={} result={} memberId={} queueId={} previousAttempt={} deliveryAttempt={}",
                LogEvent.RETRY_NOTIFICATION_DELIVERY,
                LogResult.SUCCESS,
                adminId,
                queue.getId(),
                previousAttempt,
                newAttempt
        );
        return toResponse(queue);
    }

    private NotificationDeliveryResponse toResponse(NotificationQueue queue) {
        return new NotificationDeliveryResponse(
                queue.getId(),
                queue.getEventKey(),
                queue.getNotificationType(),
                queue.getTargetType(),
                queue.getTargetId(),
                queue.getStatus(),
                maskEmail(queue.getRecipientEmail()),
                safeAttempt(queue),
                queue.getRetryCount() == null ? 0 : queue.getRetryCount(),
                queue.getMaxAttempts() == null ? 0 : queue.getMaxAttempts(),
                queue.getNextAttemptAt(),
                queue.getLastAttemptAt(),
                queue.getSentAt(),
                queue.getDeliveredAt(),
                queue.getProviderMessageId(),
                queue.getLastError(),
                queue.getCreatedAt(),
                queue.getUpdatedAt()
        );
    }

    private NotificationQueueStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return NotificationQueueStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.INVALID_NOTIFICATION_DELIVERY_FILTER);
        }
    }

    private NotificationType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return NotificationType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.INVALID_NOTIFICATION_DELIVERY_FILTER);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new AppException(ErrorCode.INVALID_NOTIFICATION_DELIVERY_FILTER);
        }
    }

    private int safeAttempt(NotificationQueue queue) {
        return queue.getDeliveryAttempt() == null ? 1 : Math.max(queue.getDeliveryAttempt(), 1);
    }

    private long count(Map<String, Long> counts, NotificationQueueStatus status) {
        return counts.getOrDefault(status.name(), 0L);
    }

    private String maskEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return null;
        }
        String normalized = email.strip();
        int separator = normalized.indexOf('@');
        if (separator <= 0 || separator == normalized.length() - 1) {
            return "***";
        }
        String local = normalized.substring(0, separator);
        String domain = normalized.substring(separator + 1);
        String visibleLocal = local.length() == 1 ? "*" : local.charAt(0) + "***";
        return visibleLocal + "@" + domain;
    }
}
