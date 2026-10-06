package com.vn.rag.client;

public interface RagIngestionClient {

    IngestionResponse ingestLibraryEbook(IngestionRequest request);

    IngestionStatusResponse getIngestionStatus(Long ingestionJobId);

    record IngestionRequest(
            String sourceType,
            Long bookId,
            Long ebookId,
            String bucket,
            String objectKey,
            String originalFilename,
            String contentType,
            Long fileSizeBytes,
            String checksumSha256,
            boolean forceReindex
    ) {
        public static IngestionRequest libraryEbook(Long bookId, Long ebookId, String bucket,
                                                     String objectKey, String originalFilename,
                                                     String contentType, Long fileSizeBytes,
                                                     String checksumSha256) {
            return new IngestionRequest(
                    "LIBRARY_EBOOK", bookId, ebookId, bucket, objectKey, originalFilename,
                    contentType, fileSizeBytes, checksumSha256, false
            );
        }

        public static IngestionRequest libraryEbookReindex(Long bookId, Long ebookId, String bucket,
                                                            String objectKey, String originalFilename,
                                                            String contentType, Long fileSizeBytes,
                                                            String checksumSha256) {
            return new IngestionRequest(
                    "LIBRARY_EBOOK", bookId, ebookId, bucket, objectKey, originalFilename,
                    contentType, fileSizeBytes, checksumSha256, true
            );
        }
    }

    record IngestionResponse(
            String documentId,
            Long ingestionJobId,
            String status
    ) {
    }

    record IngestionStatusResponse(
            String documentId,
            Long ingestionJobId,
            String status,
            String stage,
            String errorCode,
            String errorMessage
    ) {
    }
}
