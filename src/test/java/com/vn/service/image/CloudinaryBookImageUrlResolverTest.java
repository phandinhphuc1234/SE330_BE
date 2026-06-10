package com.vn.service.image;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.vn.dto.catalog.response.BookCoverImageResponse;
import com.vn.entity.BookImage;
import com.vn.enums.BookImageType;
import com.vn.enums.ImageProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudinaryBookImageUrlResolverTest {

    private final Cloudinary cloudinary = new Cloudinary(ObjectUtils.asMap(
            "cloud_name", "dwgv3yx7e",
            "api_key", "test-key",
            "api_secret", "test-secret",
            "secure", true,
            "analytics", false
    ));
    private final CloudinaryImageUrlBuilder urlBuilder = new CloudinaryImageUrlBuilder(cloudinary);
    private final CloudinaryBookImageUrlResolver resolver = new CloudinaryBookImageUrlResolver(urlBuilder);

    @Test
    void resolve_shouldBuildVersionlessCoverUrlsFromPublicId() {
        BookImage image = BookImage.builder()
                .provider(ImageProvider.CLOUDINARY)
                .publicId("9780465050659")
                .secureUrl("https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,w_320,h_480,q_auto,f_auto/9780465050659.png")
                .assetType(BookImageType.COVER_FRONT)
                .format("png")
                .altText("Book cover for The Design of Everyday Things")
                .primaryImage(true)
                .sortOrder(0)
                .build();

        BookCoverImageResponse response = resolver.resolve(image);

        assertThat(response.originalUrl())
                .isEqualTo("https://res.cloudinary.com/dwgv3yx7e/image/upload/9780465050659.png");
        assertThat(response.thumbnailUrl())
                .isEqualTo("https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,f_auto,h_480,q_auto,w_320/9780465050659.png");
        assertThat(response.detailUrl())
                .isEqualTo("https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,f_auto,h_1200,q_auto,w_800/9780465050659.png");
        assertThat(response.altText()).isEqualTo("Book cover for The Design of Everyday Things");
    }

    @Test
    void resolve_shouldDefaultToPng_whenFormatMissing() {
        BookImage image = BookImage.builder()
                .provider(ImageProvider.CLOUDINARY)
                .publicId("9780465050659")
                .secureUrl("https://res.cloudinary.com/dwgv3yx7e/image/upload/9780465050659.png")
                .assetType(BookImageType.COVER_FRONT)
                .altText("Book cover")
                .primaryImage(true)
                .sortOrder(0)
                .build();

        BookCoverImageResponse response = resolver.resolve(image);

        assertThat(response.thumbnailUrl()).endsWith("/9780465050659.png");
        assertThat(response.detailUrl()).endsWith("/9780465050659.png");
    }
}
