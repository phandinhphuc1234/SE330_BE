package com.vn.service.storage;

public enum MediaDeliveryType {
    // Asset public bình thường, dùng cho ảnh bìa sách.
    UPLOAD("upload"),
    // Asset cần signed URL khi delivery, dùng cho ebook PDF protected.
    AUTHENTICATED("authenticated"),
    // Delivery type private của Cloudinary, để sẵn nếu sau này cần chính sách bảo vệ khác.
    PRIVATE("private");

    private final String cloudinaryValue;

    MediaDeliveryType(String cloudinaryValue) {
        this.cloudinaryValue = cloudinaryValue;
    }

    public String cloudinaryValue() {
        return cloudinaryValue;
    }
}
