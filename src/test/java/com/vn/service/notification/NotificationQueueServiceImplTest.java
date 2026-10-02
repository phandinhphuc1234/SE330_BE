package com.vn.service.notification;

import com.vn.entity.Member;
import com.vn.entity.Notification;
import com.vn.entity.NotificationQueue;
import com.vn.enums.NotificationQueueStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import com.vn.repository.NotificationQueueRepository;
import com.vn.repository.NotificationRepository;
import com.vn.service.impl.notification.NotificationEventKeyLock;
import com.vn.service.impl.notification.NotificationPayloadGuard;
import com.vn.service.impl.notification.NotificationQueueServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationQueueServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationQueueRepository notificationQueueRepository;

    @Mock
    private NotificationEventKeyLock eventKeyLock;

    private NotificationQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationQueueServiceImpl(
                notificationRepository,
                notificationQueueRepository,
                eventKeyLock,
                new NotificationPayloadGuard(),
                com.vn.testsupport.TestTime.CLOCK
        );
    }

    @Test
    void enqueueEmailShouldCreateInAppNotificationAndDurableQueue() {
        EmailNotificationCommand command = accountBannedCommand();
        when(notificationQueueRepository.findByEventKey(command.eventKey())).thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(10L);
            return notification;
        });
        when(notificationQueueRepository.save(any(NotificationQueue.class))).thenAnswer(invocation -> {
            NotificationQueue queue = invocation.getArgument(0);
            queue.setId(20L);
            return queue;
        });

        NotificationEnqueueResult result = service.enqueueEmail(command);

        assertThat(result).isEqualTo(new NotificationEnqueueResult(10L, 20L, true));
        verify(eventKeyLock).acquire(command.eventKey());

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getTitle()).isEqualTo("Tài khoản đã bị khóa");
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.ACCOUNT_BANNED);

        ArgumentCaptor<NotificationQueue> queueCaptor = ArgumentCaptor.forClass(NotificationQueue.class);
        verify(notificationQueueRepository).save(queueCaptor.capture());
        NotificationQueue queue = queueCaptor.getValue();
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.PENDING);
        assertThat(queue.getEventKey()).isEqualTo("ACCOUNT_BANNED:501:EMAIL");
        assertThat(queue.getRecipientEmail()).isEqualTo("reader@example.com");
        assertThat(queue.getTemplateCode()).isEqualTo("account-banned");
        assertThat(queue.getPayload()).containsEntry("reason", "Vi phạm quy định");
        assertThat(queue.getMaxAttempts()).isEqualTo(5);
        assertThat(queue.getScheduledAt()).isEqualTo(com.vn.testsupport.TestTime.NOW);
        assertThat(queue.getNextAttemptAt()).isEqualTo(com.vn.testsupport.TestTime.NOW);
    }

    @Test
    void enqueueEmailShouldReturnExistingQueueForTheSameEventKey() {
        EmailNotificationCommand command = accountBannedCommand();
        Notification notification = Notification.builder().id(10L).build();
        NotificationQueue existingQueue = NotificationQueue.builder()
                .id(20L)
                .notification(notification)
                .eventKey(command.eventKey())
                .build();
        when(notificationQueueRepository.findByEventKey(command.eventKey()))
                .thenReturn(Optional.of(existingQueue));

        NotificationEnqueueResult result = service.enqueueEmail(command);

        assertThat(result).isEqualTo(new NotificationEnqueueResult(10L, 20L, false));
        verify(eventKeyLock).acquire(command.eventKey());
        verify(notificationRepository, never()).save(any());
        verify(notificationQueueRepository, never()).save(any());
    }

    @Test
    void enqueueEmailShouldRejectSensitivePayloadBeforeWritingAnything() {
        EmailNotificationCommand command = EmailNotificationCommand.builder()
                .member(member())
                .title("Tài khoản đã bị khóa")
                .content("Tài khoản của bạn đã bị khóa.")
                .notificationType(NotificationType.ACCOUNT_BANNED)
                .targetType(NotificationTargetType.MEMBER_STATUS_AUDIT)
                .targetId(501L)
                .eventKey("ACCOUNT_BANNED:501:EMAIL")
                .templateCode("account-banned")
                .payload(Map.of("security", Map.of("accessToken", "must-not-be-stored")))
                .build();

        assertThatThrownBy(() -> service.enqueueEmail(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessToken");

        verify(eventKeyLock, never()).acquire(any());
        verify(notificationRepository, never()).save(any());
        verify(notificationQueueRepository, never()).save(any());
    }

    private EmailNotificationCommand accountBannedCommand() {
        return EmailNotificationCommand.builder()
                .member(member())
                .title("Tài khoản đã bị khóa")
                .content("Tài khoản của bạn đã bị khóa.")
                .notificationType(NotificationType.ACCOUNT_BANNED)
                .targetType(NotificationTargetType.MEMBER_STATUS_AUDIT)
                .targetId(501L)
                .eventKey("ACCOUNT_BANNED:501:EMAIL")
                .templateCode("account-banned")
                .payload(Map.of("reason", "Vi phạm quy định"))
                .build();
    }

    private Member member() {
        return Member.builder()
                .id(7L)
                .email("reader@example.com")
                .build();
    }
}
