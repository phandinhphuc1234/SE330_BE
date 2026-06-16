package com.vn.dto.ebook.response;

import java.time.Instant;

// Response khi user chủ động đóng reader.
public record EbookReadingSessionCloseResponse(
        // Session đã đóng.
        Long sessionId,
        // Trạng thái sau close, thường là CLOSED nếu trước đó còn ACTIVE.
        String status,
        // Thời điểm backend ghi nhận đóng session.
        Instant closedAt,
        // Giờ server để frontend đồng bộ state nếu cần.
        Instant serverNow
) {
}
