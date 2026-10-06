package com.vn.notification.entity;

import com.vn.member.entity.Member;

import com.vn.notification.enums.NotificationChannel;
import com.vn.notification.enums.NotificationQueueStatus;
import com.vn.notification.enums.NotificationTargetType;
import com.vn.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationQueueTest {

    @Test
    void prePersistShouldCreateBackwardCompatibleDeliveryDefaults() {
        Member member = Member.builder()
                .email("reader@example.com")
                .build();

        NotificationQueue queue = NotificationQueue.builder()
                .member(member)
                .channel(NotificationChannel.EMAIL)
                .notificationType(NotificationType.DUE_SOON_REMINDER)
                .targetType(NotificationTargetType.BORROW_RECORD)
                .targetId(42L)
                .build();

        queue.prePersist();

        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.PENDING);
        assertThat(queue.getRetryCount()).isZero();
        assertThat(queue.getMaxAttempts()).isEqualTo(5);
        assertThat(queue.getScheduledAt()).isNotNull();
        assertThat(queue.getNextAttemptAt()).isEqualTo(queue.getScheduledAt());
        assertThat(queue.getEventKey()).isEqualTo("DUE_SOON_REMINDER:BORROW_RECORD:42:EMAIL");
        assertThat(queue.getProviderRequestKey()).isEqualTo(queue.getEventKey());
        assertThat(queue.getDeliveryAttempt()).isEqualTo(1);
        assertThat(queue.getRecipientEmail()).isEqualTo("reader@example.com");
        assertThat(queue.getTemplateCode()).isEqualTo("due-soon-reminder");
        assertThat(queue.getPayload()).isEmpty();
        assertThat(queue.getCreatedAt()).isNotNull();
        assertThat(queue.getUpdatedAt()).isNotNull();
    }
}
