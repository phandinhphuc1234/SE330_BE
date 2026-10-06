package com.vn.ebook.storage;

import com.vn.ebook.config.ObjectStorageProperties;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.FileSystems;
import java.nio.file.attribute.PosixFilePermissions;
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
        Path stagedFile = null;
        Path stagingDirectory = null;
        try {
            stagingDirectory = createStagingDirectory();
            stagedFile = Files.createTempFile(stagingDirectory, "source-", ".pdf");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(input, stagedFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // A repeatable file-backed body avoids truncated uploads when an S3-compatible
            // server retries or re-reads the request body. The multipart stream itself may
            // only be consumed once, so it is staged before the network request starts.
            ebookS3Client.putObject(request, RequestBody.fromFile(stagedFile));
            verifyUploadedSize(objectKey, file.getSize());
        } catch (IOException | RuntimeException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        } finally {
            deleteStagedFile(stagedFile);
            deleteStagedFile(stagingDirectory);
        }

        String checksum = HexFormat.of().formatHex(digest.digest());
        return new EbookObjectMetadata(
                properties.ebookBucket(), objectKey, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), checksum, Instant.now()
        );
    }

    static Path createStagingDirectory() throws IOException {
        // On production Linux only the service owner may access the staged PDF.
        // A private random parent also isolates the file from shared /tmp entries.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory("ebook-upload-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        // Windows profiles inherit the owner's private ACL rather than the
        // machine-wide temporary directory's permissions.
        return Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".ebook-upload-");
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

    private void deleteStagedFile(Path stagedFile) {
        if (stagedFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagedFile);
        } catch (IOException e) {
            // Upload outcome is already known; cleanup failure must not corrupt that result.
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
