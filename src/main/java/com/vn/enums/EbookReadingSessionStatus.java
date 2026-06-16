package com.vn.enums;

// Trạng thái vòng đời của một phiên đọc ebook.
// Loan là quyền đọc dài hạn; reading session chỉ là phiên đọc web ngắn hạn.
public enum EbookReadingSessionStatus {
    // Session còn hiệu lực, có thể dùng để xin signed URL đọc PDF.
    ACTIVE,
    // Session hết hạn do quá session_expires_at hoặc worker spec 06 xử lý.
    EXPIRED,
    // User chủ động đóng reader, backend xóa Redis cache và không cấp URL nữa.
    CLOSED,
    // Admin/hệ thống thu hồi quyền đọc trước hạn.
    REVOKED
}
