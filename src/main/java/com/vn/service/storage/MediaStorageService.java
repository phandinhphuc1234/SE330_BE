package com.vn.service.storage;

// Abstraction cho storage provider. Business service gọi interface này thay vì phụ thuộc trực tiếp Cloudinary.
public interface MediaStorageService {

    // Upload media lên provider và trả metadata trung lập cho business service lưu DB.
    MediaUploadResult upload(MediaUploadCommand command);

    // Tạo URL đọc protected media sau khi business service đã kiểm tra quyền truy cập.
    MediaSignedUrlResult generateSignedUrl(MediaSignedUrlCommand command);

    // Xóa asset khỏi provider theo publicId/resourceType/deliveryType đã lưu.
    void delete(MediaDeleteCommand command);
}
