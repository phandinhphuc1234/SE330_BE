package com.vn.mapper;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.dto.catalog.response.BookCoverManagementResponse;
import com.vn.entity.BookImage;
import com.vn.service.image.BookImageUrlResolverFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookImageMapper {

    private final BookImageUrlResolverFactory bookImageUrlResolverFactory;

    // Mapper chỉ biết resolve ảnh qua factory, không biết ảnh đến từ Cloudinary hay provider khác.
    public BookCoverImageResponse toCoverImageResponse(BookImage image) {
        return bookImageUrlResolverFactory.resolve(image);
    }

    public BookCoverManagementResponse toCoverManagementResponse(BookImage image, String oldImageStatus) {
        BookCoverImageResponse coverImage = toCoverImageResponse(image);
        return new BookCoverManagementResponse(
                image.getId(),
                image.getBook().getId(),
                image.getProvider().name(),
                image.getPublicId(),
                coverImage.originalUrl(),
                coverImage.thumbnailUrl(),
                coverImage.detailUrl(),
                image.getAltText(),
                image.getPrimaryImage(),
                image.getStatus().name(),
                oldImageStatus
        );
    }
}
