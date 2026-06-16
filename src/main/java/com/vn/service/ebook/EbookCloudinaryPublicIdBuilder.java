package com.vn.service.ebook;

import com.vn.config.CloudinaryProperties;
import com.vn.entity.Book;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EbookCloudinaryPublicIdBuilder {

    private static final String DEFAULT_EBOOK_FOLDER = "pdf";

    private final CloudinaryProperties properties;

    public EbookCloudinaryPublicIdBuilder(CloudinaryProperties properties) {
        this.properties = properties;
    }

    public String buildMainPdfPublicId(Book book) {
        // Convention hiện tại: pdf/{isbn}/main.pdf, ví dụ pdf/9780132350884/main.pdf.
        return ebookFolder() + "/" + sanitizeBookIdentifier(book) + "/main.pdf";
    }

    private String ebookFolder() {
        String configuredFolder = properties.ebookFolder();
        if (!StringUtils.hasText(configuredFolder)) {
            return DEFAULT_EBOOK_FOLDER;
        }

        return configuredFolder.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private String sanitizeBookIdentifier(Book book) {
        String isbn = book.getIsbn();
        if (StringUtils.hasText(isbn)) {
            // Bỏ dấu gạch/khoảng trắng trong ISBN để đường dẫn Cloudinary ổn định.
            String normalizedIsbn = isbn.replaceAll("[^0-9Xx]", "");
            if (StringUtils.hasText(normalizedIsbn)) {
                return normalizedIsbn;
            }
        }

        return "book-" + book.getId();
    }
}
