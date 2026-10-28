package com.vn.ebook.storage;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.io.InputStream;

public interface EbookObjectStorageService {

    EbookObjectMetadata upload(String objectKey, MultipartFile file);

    String createReadUrl(String bucket, String objectKey, Duration ttl);

    EbookReadMetadata describeRead(String bucket, String objectKey);

    InputStream openRead(String bucket, String objectKey, String range, String etag, long expectedLength);

    record EbookReadMetadata(long contentLength, String etag) {
    }

    void delete(String bucket, String objectKey);

    record EbookObjectMetadata(
            String bucket,
            String objectKey,
            String originalFilename,
            String contentType,
            long fileSizeBytes,
            String checksumSha256,
            Instant uploadedAt
    ) {
    }
}
