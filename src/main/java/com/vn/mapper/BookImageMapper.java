package com.vn.mapper;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.service.image.ImageUrlResolverFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookImageMapper {

    private final ImageUrlResolverFactory imageUrlResolverFactory;

    // Mapper chỉ biết resolve ảnh qua factory, không biết ảnh đến từ Cloudinary hay provider khác.
    public BookCoverImageResponse toCoverImageResponse(BookImage image) {
        return imageUrlResolverFactory.resolve(image);
    }
}
