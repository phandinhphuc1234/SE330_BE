package com.vn.service.storage.ebook;

import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;

public interface EbookObjectStorageService {

    EbookObjectMetadata upload(String objectKey, MultipartFile file);

    String createReadUrl(String bucket, String objectKey, Duration ttl);

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
