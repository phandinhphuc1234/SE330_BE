package com.vn.dto.ebook.response;

import java.time.Instant;

// Response tạo reader session.
// Đây là response duy nhất trả raw sessionToken cho frontend.
public record EbookReadingSessionResponse(
        // ID session trong DB, frontend dùng cho refresh/close.
        Long sessionId,
        // Raw token đặt vào header X-Reading-Session ở các request reader tiếp theo.
        String sessionToken,
        // Book đang đọc, giúp frontend kiểm tra response khớp trang hiện tại.
        Long bookId,
        // Ebook/PDF cụ thể đang đọc.
        Long bookEbookId,
        // Loan cấp quyền đọc cho session này.
        Long loanId,
        // Hạn quyền đọc ebook.
        Instant loanExpiresAt,
        // Hạn phiên đọc ngắn hạn hiện tại.
        Instant sessionExpiresAt,
        // Giờ server để frontend tính countdown tránh lệch clock client.
        Instant serverNow
) {
}
