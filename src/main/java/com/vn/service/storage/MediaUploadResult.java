package com.vn.service.storage;

// Metadata provider trả về sau upload. Service nghiệp vụ chọn field cần lưu vào bảng riêng của nó.
public record MediaUploadResult(
        String publicId,
        String secureUrl,
        Long version,
        String format,
        String resourceType,
        String originalFilename,
        Integer width,
        Integer height,
        Long duration,
        Long sizeBytes,
        String mimeType
) {
}
