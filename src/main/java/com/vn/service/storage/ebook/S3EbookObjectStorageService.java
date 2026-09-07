package com.vn.service.storage.ebook;

import com.vn.config.ObjectStorageProperties;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class S3EbookObjectStorageService implements EbookObjectStorageService {

    private final S3Client ebookS3Client;
    private final S3Presigner ebookS3Presigner;
    private final ObjectStorageProperties properties;

    @Override
    public EbookObjectMetadata upload(String objectKey, MultipartFile file) {
        MessageDigest digest = sha256Digest();
        Map<String, String> metadata = objectMetadata(objectKey, file);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.ebookBucket())
                .key(objectKey)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .metadata(metadata)
                .build();
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            ebookS3Client.putObject(request, RequestBody.fromInputStream(input, file.getSize()));
        } catch (IOException | RuntimeException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        verifyUploadedSize(objectKey, file.getSize());
        String checksum = HexFormat.of().formatHex(digest.digest());
        return new EbookObjectMetadata(
                properties.ebookBucket(), objectKey, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), checksum, Instant.now()
        );
    }

    @Override
    public String createReadUrl(String bucket, String objectKey, Duration ttl) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(builder -> builder.bucket(bucket).key(objectKey))
                .build();
        return ebookS3Presigner.presignGetObject(request).url().toString();
    }

    @Override
    public void delete(String bucket, String objectKey) {
        ebookS3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String safeMetadataValue(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private void verifyUploadedSize(String objectKey, long expectedSize) {
        try {
            HeadObjectResponse object = ebookS3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.ebookBucket())
                    .key(objectKey)
                    .build());
            if (object.contentLength() != expectedSize) {
                throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
        } catch (RuntimeException e) {
            if (e instanceof AppException appException) {
                throw appException;
            }
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Map<String, String> objectMetadata(String objectKey, MultipartFile file) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("original-filename", safeMetadataValue(file.getOriginalFilename()));

        String[] parts = objectKey.split("/");
        if (parts.length == 4 && "ebooks".equals(parts[0]) && "original.pdf".equals(parts[3])) {
            metadata.put("book-id", parts[1]);
            metadata.put("ebook-id", parts[2]);
        }

        return metadata;
    }
}
