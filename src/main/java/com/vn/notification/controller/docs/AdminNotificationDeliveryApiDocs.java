package com.vn.notification.controller.docs;

import com.vn.shared.dto.ApiResponse;
import com.vn.notification.dto.response.NotificationDeliveryResponse;
import com.vn.notification.dto.response.NotificationDeliverySummaryResponse;
import com.vn.auth.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Admin Notification Delivery", description = "Admin operations for email delivery queues")
@SecurityRequirement(name = "Bearer Authentication")
public interface AdminNotificationDeliveryApiDocs {

    @Operation(summary = "Search email deliveries")
    ResponseEntity<ApiResponse<List<NotificationDeliveryResponse>>> search(
            @Parameter(description = "Event key, recipient email or provider message ID") String query,
            @Parameter(description = "Queue status") String status,
            @Parameter(description = "Notification type") String notificationType,
            int page,
            int size
    );

    @Operation(summary = "Get email delivery summary")
    ResponseEntity<ApiResponse<NotificationDeliverySummaryResponse>> getSummary();

    @Operation(
            summary = "Retry a dead email delivery",
            description = "Creates a new provider delivery attempt while preserving the business event identity."
    )
    ResponseEntity<ApiResponse<NotificationDeliveryResponse>> retry(
            Long queueId,
            MemberUserDetails userDetails
    );
}
