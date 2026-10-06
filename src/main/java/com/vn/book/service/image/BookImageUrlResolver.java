package com.vn.book.service.image;

import com.vn.book.dto.response.BookCoverImageResponse;
import com.vn.book.entity.BookImage;
import com.vn.book.enums.ImageProvider;

public interface BookImageUrlResolver {

    // Mỗi resolver tự khai báo provider nó hỗ trợ để factory chọn đúng implementation.
    ImageProvider supports();

    // Chuyển metadata ảnh sách trong DB thành URL tối ưu cho frontend.
    BookCoverImageResponse resolve(BookImage image);
}
