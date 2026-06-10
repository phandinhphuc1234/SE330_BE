package com.vn.service.storage;

// Abstraction cho storage provider. Business service gọi interface này thay vì phụ thuộc trực tiếp Cloudinary.
public interface MediaStorageService {

    MediaUploadResult upload(MediaUploadCommand command);

    void delete(MediaDeleteCommand command);
}
