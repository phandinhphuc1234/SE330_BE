package com.vn.service.rag;

public interface RagIngestionClient {

    IngestionResponse ingestLibraryEbook(IngestionRequest request);

    record IngestionRequest(
            String sourceType,
            Long bookId,
            Long ebookId,
            String bucket,
            String objectKey,
            String originalFilename,
            String contentType,
            Long fileSizeBytes,
            String checksumSha256
    ) {
        public static IngestionRequest libraryEbook(Long bookId, Long ebookId, String bucket,
                                                     String objectKey, String originalFilename,
                                                     String contentType, Long fileSizeBytes,
                                                     String checksumSha256) {
            return new IngestionRequest(
                    "LIBRARY_EBOOK", bookId, ebookId, bucket, objectKey, originalFilename,
                    contentType, fileSizeBytes, checksumSha256
            );
        }
    }

    record IngestionResponse(
            String documentId,
            Long ingestionJobId,
            String status
    ) {
    }
}
