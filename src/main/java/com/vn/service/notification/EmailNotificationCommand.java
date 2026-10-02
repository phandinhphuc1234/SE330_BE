package com.vn.service.notification;

import com.vn.entity.Member;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import lombok.Builder;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Builder
public record EmailNotificationCommand(
        Member member,
        String title,
        String content,
        NotificationType notificationType,
        NotificationTargetType targetType,
        Long targetId,
        String eventKey,
        String templateCode,
        Map<String, Object> payload,
        Instant scheduledAt,
        Integer maxAttempts
) {

    public EmailNotificationCommand {
        member = Objects.requireNonNull(member, "member is required");
        notificationType = Objects.requireNonNull(notificationType, "notificationType is required");
        targetType = Objects.requireNonNull(targetType, "targetType is required");
        targetId = requirePositive(targetId, "targetId");
        title = requireText(title, "title", 255);
        content = requireText(content, "content", null);
        eventKey = requireText(eventKey, "eventKey", 255);
        templateCode = requireText(templateCode, "templateCode", 100);
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        scheduledAt = scheduledAt == null ? Instant.now() : scheduledAt;
        maxAttempts = maxAttempts == null ? 5 : requirePositive(maxAttempts, "maxAttempts");
    }

    private static String requireText(String value, String field, Integer maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.strip();
        if (maxLength != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static Long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static Integer requirePositive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
