package com.vn.service.storage;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

// Command trung lập cho mọi loại media. Business service tự validate file và tự sinh publicId,
// còn storage implementation chỉ dùng dữ liệu này để gọi provider như Cloudinary/S3 sau này.
public record MediaUploadCommand(
        MultipartFile file,
        // Cloudinary cần resource type để chọn endpoint: image/upload, raw/upload, video/upload.
        MediaResourceType resourceType,
        // Category là khái niệm nghiệp vụ của hệ thống, ví dụ BOOK_COVER hoặc BOOK_PDF.
        MediaCategory category,
        String publicId,
        // Tags/context là metadata phụ gửi lên provider, không phải dữ liệu quyết định trong DB.
        Map<String, String> tags,
        Map<String, String> context
) {
}
