package com.vn.service.storage;

import java.time.Instant;

// Command ký URL đọc media protected.
// Business service truyền metadata; storage provider lo format/ký URL theo từng provider.
public record MediaSignedUrlCommand(
        // Cloudinary public_id của ebook PDF, lấy từ DB chứ không nhận từ frontend.
        String publicId,
        // Ebook PDF đang dùng RAW.
        MediaResourceType resourceType,
        // Ebook PDF protected dùng AUTHENTICATED/PRIVATE tùy cấu hình asset.
        MediaDeliveryType deliveryType,
        // Format thường là pdf; helper sẽ tránh append .pdf hai lần.
        String format,
        // Hạn URL do backend quyết định từ session/loan TTL.
        Instant expiresAt
) {
}
