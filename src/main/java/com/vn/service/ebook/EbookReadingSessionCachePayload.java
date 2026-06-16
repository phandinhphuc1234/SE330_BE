package com.vn.service.ebook;

import java.time.Instant;

// Payload cache Redis cho session đọc.
// PostgreSQL vẫn là source of truth khi cache miss hoặc cache bị lỗi.
public record EbookReadingSessionCachePayload(
        // ID session thật trong PostgreSQL, dùng để map về row audit/debug.
        Long sessionId,
        // Member sở hữu session; phải khớp JWT của request hiện tại.
        Long memberId,
        // Book route hiện tại; chống dùng token của sách này để đọc sách khác.
        Long bookId,
        // Ebook/PDF cụ thể của book đang đọc.
        Long bookEbookId,
        // Loan cấp quyền đọc; mỗi lần cấp signed URL vẫn load loan từ DB để check realtime.
        Long loanId,
        // Hạn session ngắn hạn.
        Instant sessionExpiresAt,
        // Hạn loan dài hơn; Redis TTL lấy min(sessionExpiresAt, loanExpiresAt).
        Instant loanExpiresAt,
        // ACTIVE mới được dùng; CLOSED/EXPIRED/REVOKED không được nạp lại cache.
        String status
) {
}
