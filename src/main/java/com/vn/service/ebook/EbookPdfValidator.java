package com.vn.service.ebook;

import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@Component
public class EbookPdfValidator {

    private static final long MAX_PDF_SIZE_BYTES = 100L * 1024 * 1024;
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String PDF_EXTENSION = "pdf";

    public void validate(MultipartFile file) {
        // Rule upload ebook khác ảnh bìa: chỉ nhận PDF và giới hạn 100MB.
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_PDF_SIZE_BYTES) {
            throw new AppException(ErrorCode.INVALID_EBOOK_FILE);
        }

        // MVP kiểm tra content-type + extension; production có thể bổ sung magic bytes/Tika.
        if (!PDF_CONTENT_TYPE.equalsIgnoreCase(file.getContentType())) {
            throw new AppException(ErrorCode.INVALID_EBOOK_FILE);
        }

        if (!PDF_EXTENSION.equals(extractExtension(file.getOriginalFilename()))) {
            throw new AppException(ErrorCode.INVALID_EBOOK_FILE);
        }
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }

        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }

        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
