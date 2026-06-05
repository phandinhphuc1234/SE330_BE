package com.vn.service.image;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;

public interface ImageUrlResolver {

    // Mỗi resolver tự khai báo provider nó hỗ trợ để factory chọn đúng implementation.
    ImageProvider supports();

    // Chuyển metadata ảnh trong DB thành URL tối ưu cho frontend.
    BookCoverImageResponse resolve(BookImage image);
}
