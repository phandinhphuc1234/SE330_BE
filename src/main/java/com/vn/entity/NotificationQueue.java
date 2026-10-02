package com.vn.entity;

import com.vn.enums.NotificationChannel;
import com.vn.enums.NotificationQueueStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Entity
@Table(name = "notification_queue")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id")
    private Notification notification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationQueueStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", length = 100)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 100)
    private NotificationTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    // Snapshot the destination at enqueue time so a later profile change does
    // not silently redirect an already committed security notification.
    @Column(name = "recipient_email", length = 320)
    private String recipientEmail;

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Column(name = "delivery_attempt", nullable = false)
    private Integer deliveryAttempt;

    @Column(name = "provider_request_key", nullable = false, length = 255)
    private String providerRequestKey;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (this.status == null) {
            this.status = NotificationQueueStatus.PENDING;
        }
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        if (this.maxAttempts == null || this.maxAttempts < 1) {
            this.maxAttempts = 5;
        }
        if (this.scheduledAt == null) {
            this.scheduledAt = now;
        }
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = this.scheduledAt;
        }
        if (this.eventKey == null || this.eventKey.isBlank()) {
            this.eventKey = buildDefaultEventKey();
        }
        if (this.deliveryAttempt == null || this.deliveryAttempt < 1) {
            this.deliveryAttempt = 1;
        }
        if (this.providerRequestKey == null || this.providerRequestKey.isBlank()) {
            this.providerRequestKey = this.eventKey;
        }
        if ((this.recipientEmail == null || this.recipientEmail.isBlank()) && this.member != null) {
            this.recipientEmail = this.member.getEmail();
        }
        if (this.templateCode == null || this.templateCode.isBlank()) {
            this.templateCode = buildDefaultTemplateCode();
        }
        if (this.payload == null) {
            this.payload = new LinkedHashMap<>();
        }
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
        if (this.payload == null) {
            this.payload = new LinkedHashMap<>();
        }
    }

    private String buildDefaultEventKey() {
        if (this.notificationType == null || this.targetType == null || this.targetId == null || this.channel == null) {
            throw new IllegalStateException(
                    "Notification queue eventKey is required when no complete business target is available"
            );
        }
        return this.notificationType.name()
                + ":" + this.targetType.name()
                + ":" + this.targetId
                + ":" + this.channel.name();
    }

    private String buildDefaultTemplateCode() {
        if (this.notificationType == null) {
            throw new IllegalStateException(
                    "Notification queue templateCode is required when notificationType is unavailable"
            );
        }
        return this.notificationType.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
