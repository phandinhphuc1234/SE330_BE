package com.vn.ebook.enums;

public enum EbookIngestionStatus {
    NOT_REQUESTED,
    QUEUED,
    PROCESSING,
    PARSED,
    CHUNKED,
    EMBEDDING,
    EMBEDDED,
    INDEXING,
    INDEXED,
    INDEX_FAILED
}
