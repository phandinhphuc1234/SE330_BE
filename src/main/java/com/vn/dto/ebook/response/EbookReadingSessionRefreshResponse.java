package com.vn.dto.ebook.response;

import java.time.Instant;

// Response heartbeat/refresh.
// Không trả lại raw token để giữ rule: raw session token chỉ xuất hiện khi create session.
public record EbookReadingSessionRefreshResponse(
        // Session vừa được refresh.
        Long sessionId,
        // Trạng thái sau refresh, hiện tại hợp lệ sẽ là ACTIVE.
        String status,
        // Hạn loan để frontend biết phiên đọc không thể vượt quá mốc này.
        Instant loanExpiresAt,
        // Hạn session mới sau refresh.
        Instant sessionExpiresAt,
        // Giờ server để frontend tính countdown ổn định.
        Instant serverNow
) {
}
