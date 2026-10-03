package com.vn.notification.controller;

import com.vn.notification.controller.docs.AdminNotificationDeliveryApiDocs;
import com.vn.shared.dto.ApiResponse;
import com.vn.shared.dto.PageMeta;
import com.vn.notification.dto.response.NotificationDeliveryResponse;
import com.vn.notification.dto.response.NotificationDeliverySummaryResponse;
import com.vn.auth.security.MemberUserDetails;
import com.vn.notification.service.NotificationDeliveryAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/notification-deliveries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationDeliveryController implements AdminNotificationDeliveryApiDocs {

    private final NotificationDeliveryAdminService service;

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationDeliveryResponse>>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String notificationType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<NotificationDeliveryResponse> deliveries = service.search(
                query,
                status,
                notificationType,
                page,
                size
        );
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy danh sách trạng thái gửi email thành công",
                deliveries.getContent(),
                PageMeta.from(deliveries)
        ));
    }

    @Override
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<NotificationDeliverySummaryResponse>> getSummary() {
        return ResponseEntity.ok(ApiResponse.success(
                "Lấy tổng quan gửi email thành công",
                service.getSummary()
        ));
    }

    @Override
    @PostMapping("/{queueId}/retry")
    public ResponseEntity<ApiResponse<NotificationDeliveryResponse>> retry(
            @PathVariable Long queueId,
            @AuthenticationPrincipal MemberUserDetails userDetails
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "Đã đưa email vào hàng đợi gửi lại",
                service.retryDeadDelivery(userDetails.getMember().getId(), queueId)
        ));
    }
}
