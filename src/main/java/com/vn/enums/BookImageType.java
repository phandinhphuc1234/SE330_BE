package com.vn.enums;

public enum BookImageType {
    // Ảnh bìa chính phía trước, dùng làm imageUrl mặc định của sách.
    COVER_FRONT,
    // Dự phòng cho bìa sau nếu sau này frontend cần gallery ảnh sách.
    COVER_BACK,
    // Ảnh preview/scan một vài trang mẫu, không dùng làm bìa chính.
    PREVIEW,
    // Loại mở rộng cho các ảnh chưa có nhóm nghiệp vụ riêng.
    OTHER
}
