package com.vn.service.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CloudinaryImageUrlBuilder {

    private static final String DEFAULT_FORMAT = "png";
    private static final String THUMBNAIL_TRANSFORMATION = "c_fit,w_320,h_480,q_auto,f_auto";
    private static final String DETAIL_TRANSFORMATION = "c_fit,w_800,h_1200,q_auto,f_auto";

    private final String cloudName;

    public CloudinaryImageUrlBuilder(@Value("${cloudinary.cloud-name}") String cloudName) {
        this.cloudName = cloudName;
    }

    public String thumbnailUrl(String publicId, String format) {
        return buildImageUrl(publicId, format, THUMBNAIL_TRANSFORMATION);
    }

    public String detailUrl(String publicId, String format) {
        return buildImageUrl(publicId, format, DETAIL_TRANSFORMATION);
    }

    public String originalUrl(String publicId, String format) {
        return buildImageUrl(publicId, format, null);
    }

    // Dùng versionless URL vì mỗi asset Cloudinary có thể có version khác nhau.
    private String buildImageUrl(String publicId, String format, String transformation) {
        if (!StringUtils.hasText(publicId)) {
            return null;
        }
        if (!StringUtils.hasText(cloudName)) {
            throw new IllegalStateException("Cloudinary cloud name is not configured");
        }

        String safeFormat = StringUtils.hasText(format) ? format : DEFAULT_FORMAT;
        String transformationPath = StringUtils.hasText(transformation) ? transformation + "/" : "";
        return "https://res.cloudinary.com/" + cloudName
                + "/image/upload/"
                + transformationPath
                + publicId + "." + safeFormat;
    }
}
