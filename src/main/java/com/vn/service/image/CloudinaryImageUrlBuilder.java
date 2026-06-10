package com.vn.service.image;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class CloudinaryImageUrlBuilder {

    private static final String DEFAULT_FORMAT = "png";

    private final Cloudinary cloudinary;

    public String thumbnailUrl(String publicId, String format) {
        return buildImageUrl(
                publicId,
                format,
                new Transformation<>()
                        .crop("fit")
                        .width(320)
                        .height(480)
                        .quality("auto")
                        .fetchFormat("auto")
        );
    }

    public String detailUrl(String publicId, String format) {
        return buildImageUrl(
                publicId,
                format,
                new Transformation<>()
                        .crop("fit")
                        .width(800)
                        .height(1200)
                        .quality("auto")
                        .fetchFormat("auto")
        );
    }

    public String originalUrl(String publicId, String format) {
        return buildImageUrl(publicId, format, null);
    }

    private String buildImageUrl(String publicId, String format, Transformation<?> transformation) {
        if (!StringUtils.hasText(publicId)) {
            return null;
        }
        if (!StringUtils.hasText(cloudinary.config.cloudName)) {
            throw new IllegalStateException("Cloudinary cloud name is not configured");
        }

        String publicIdWithFormat = appendFormat(publicId, format);
        var url = cloudinary.url()
                .secure(true)
                .resourceType("image");

        if (transformation != null) {
            url.transformation(transformation);
        }

        // SDK build URL giúp tránh tự nối sai transformation syntax khi sau này đổi option.
        return url.generate(publicIdWithFormat);
    }

    private String appendFormat(String publicId, String format) {
        String safeFormat = StringUtils.hasText(format) ? format : DEFAULT_FORMAT;
        String suffix = "." + safeFormat;
        return publicId.endsWith(suffix) ? publicId : publicId + suffix;
    }
}
