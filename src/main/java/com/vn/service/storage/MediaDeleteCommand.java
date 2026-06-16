package com.vn.service.storage;

// Delete command cần resourceType vì Cloudinary xóa image/raw/video bằng endpoint khác nhau.
public record MediaDeleteCommand(
        String publicId,
        MediaResourceType resourceType,
        MediaDeliveryType deliveryType,
        // invalidate=true yêu cầu CDN cache cũ bị vô hiệu hóa sau khi xóa asset.
        boolean invalidate
) {
    public MediaDeleteCommand(String publicId, MediaResourceType resourceType, boolean invalidate) {
        this(publicId, resourceType, MediaDeliveryType.UPLOAD, invalidate);
    }
}
