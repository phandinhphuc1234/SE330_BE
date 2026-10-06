package com.vn.notification.controller;

import com.vn.shared.dto.ApiResponse;
import com.vn.notification.dto.response.NotificationDeliveryResponse;
import com.vn.notification.dto.response.NotificationDeliverySummaryResponse;
import com.vn.member.entity.Member;
import com.vn.member.enums.MemberRole;
import com.vn.notification.enums.NotificationQueueStatus;
import com.vn.auth.security.MemberUserDetails;
import com.vn.notification.service.NotificationDeliveryAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNotificationDeliveryControllerTest {

    @Mock private NotificationDeliveryAdminService service;

    private AdminNotificationDeliveryController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminNotificationDeliveryController(service);
    }

    @Test
    void searchShouldReturnStandardEnvelopeAndPageMeta() {
        NotificationDeliveryResponse delivery = delivery(NotificationQueueStatus.DEAD, 1);
        when(service.search(null, "DEAD", null, 0, 20))
                .thenReturn(new PageImpl<>(List.of(delivery)));

        ResponseEntity<ApiResponse<List<NotificationDeliveryResponse>>> response =
                controller.search(null, "DEAD", null, 0, 20);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).containsExactly(delivery);
        assertThat(response.getBody().getMeta()).isNotNull();
    }

    @Test
    void retryShouldPassTheAuthenticatedAdminId() {
        Member admin = Member.builder().id(99L).role(MemberRole.ADMIN).build();
        MemberUserDetails principal = new MemberUserDetails(admin);
        NotificationDeliveryResponse retried = delivery(NotificationQueueStatus.PENDING, 2);
        when(service.retryDeadDelivery(99L, 10L)).thenReturn(retried);

        ResponseEntity<ApiResponse<NotificationDeliveryResponse>> response = controller.retry(10L, principal);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(retried);
        verify(service).retryDeadDelivery(99L, 10L);
    }

    @Test
    void controllerShouldBeRestrictedToAdmins() {
        PreAuthorize rule = AdminNotificationDeliveryController.class.getAnnotation(PreAuthorize.class);
        assertThat(rule).isNotNull();
        assertThat(rule.value()).isEqualTo("hasRole('ADMIN')");
    }

    private NotificationDeliveryResponse delivery(NotificationQueueStatus status, int attempt) {
        return new NotificationDeliveryResponse(
                10L,
                "ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL",
                null,
                null,
                1L,
                status,
                "r***@example.com",
                attempt,
                0,
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
