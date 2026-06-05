package com.vn.service.image;

import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CloudinaryImageUrlResolver implements ImageUrlResolver {

    private final CloudinaryImageUrlBuilder cloudinaryImageUrlBuilder;

    @Override
    public ImageProvider supports() {
        return ImageProvider.CLOUDINARY;
    }

    @Override
    public BookCoverImageResponse resolve(BookImage image) {
        if (image == null) {
            return null;
        }

        // originalUrl không có transformation; thumbnail/detail được build theo từng màn hình.
        return new BookCoverImageResponse(
                cloudinaryImageUrlBuilder.originalUrl(image.getPublicId(), image.getFormat()),
                cloudinaryImageUrlBuilder.thumbnailUrl(image.getPublicId(), image.getFormat()),
                cloudinaryImageUrlBuilder.detailUrl(image.getPublicId(), image.getFormat()),
                image.getAltText()
        );
    }
}
