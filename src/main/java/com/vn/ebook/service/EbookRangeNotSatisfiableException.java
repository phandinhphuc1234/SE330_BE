package com.vn.ebook.service;

public class EbookRangeNotSatisfiableException extends RuntimeException {
    private final long contentLength;

    public EbookRangeNotSatisfiableException(long contentLength) {
        super("Only one satisfiable byte range is supported.");
        this.contentLength = contentLength;
    }

    public long getContentLength() {
        return contentLength;
    }
}
