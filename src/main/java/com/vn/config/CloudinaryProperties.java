package com.vn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudinary")
public record CloudinaryProperties(
        String cloudName,
        String apiKey,
        String apiSecret,
        // Folder gốc cho ebook PDF. Mặc định đang là "pdf" để publicId thành pdf/{isbn}/main.pdf.
        String ebookFolder
) {
}
