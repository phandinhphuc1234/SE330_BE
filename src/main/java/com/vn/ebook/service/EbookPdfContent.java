package com.vn.ebook.service;

import org.springframework.core.io.Resource;

public record EbookPdfContent(Resource resource, long totalLength, long start, long end, boolean partial) {
    public long contentLength() {
        return end - start + 1;
    }
}
