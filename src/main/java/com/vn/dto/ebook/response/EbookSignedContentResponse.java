package com.vn.dto.ebook.response;

import java.time.Instant;

// Signed URL ngắn hạn để frontend fetch PDF rồi render bằng PDF.js.
public record EbookSignedContentResponse(
        // URL Cloudinary đã ký, frontend dùng để fetch PDF as ArrayBuffer.
        String signedUrl,
        // Hạn dùng URL theo backend, lấy min(5 phút, session còn lại, loan còn lại).
        Instant expiresAt,
        // Giờ server để frontend tránh tự tính TTL bằng clock client bị lệch.
        Instant serverNow
) {
}
