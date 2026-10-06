package com.vn.book.dto.response;

import java.time.Instant;
// chuẩn hóa response trả về
public record CategoryResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}

