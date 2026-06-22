package com.vn.service.rag;

public interface RagIngestionClient {

    void ingestLibraryEbook(IngestionRequest request);

    record IngestionRequest(
            String sourceType,
            Long bookId,
            Long ebookId,
            String bucket,
            String objectKey,
            String checksumSha256
    ) {
        public static IngestionRequest libraryEbook(Long bookId, Long ebookId, String bucket,
                                                     String objectKey, String checksumSha256) {
            return new IngestionRequest(
                    "LIBRARY_EBOOK", bookId, ebookId, bucket, objectKey, checksumSha256
            );
        }
    }
}
