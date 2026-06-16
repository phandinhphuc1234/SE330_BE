package com.vn.service.storage;

import java.time.Instant;

// URL đã ký và thời điểm backend xem URL này hết hạn.
public record MediaSignedUrlResult(
        // URL trả cho frontend để tải nội dung protected trong thời gian ngắn.
        String signedUrl,
        // Hạn URL được propagate lại response reader/content.
        Instant expiresAt
) {
}
