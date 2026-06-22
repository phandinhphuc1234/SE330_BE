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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class S3EbookObjectStorageService implements EbookObjectStorageService {

    private final S3Client ebookS3Client;
    private final S3Presigner ebookS3Presigner;
    private final ObjectStorageProperties properties;

    @Override
    public EbookObjectMetadata upload(String objectKey, MultipartFile file) {
        String checksum = sha256(file);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.ebookBucket())
                .key(objectKey)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();
        try (InputStream input = file.getInputStream()) {
            ebookS3Client.putObject(request, RequestBody.fromInputStream(input, file.getSize()));
        } catch (IOException | RuntimeException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
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

    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
