package com.vn.ebook.storage;

import com.vn.ebook.config.ObjectStorageProperties;
import com.vn.shared.exception.AppException;
import com.vn.ebook.storage.EbookObjectStorageService.EbookObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3EbookObjectStorageServiceTest {

    @Mock
    private S3Client ebookS3Client;

    @Mock
    private S3Presigner ebookS3Presigner;

    @Mock
    private MultipartFile file;

    private S3EbookObjectStorageService service;

    @BeforeEach
    void setUp() {
        ObjectStorageProperties properties = new ObjectStorageProperties(
                "http://localhost:8333",
                "http://localhost:8333",
                "admin",
                "secret",
                "us-east-1",
                "library-private",
                "library-temp"
        );
        service = new S3EbookObjectStorageService(ebookS3Client, ebookS3Presigner, properties);
    }

    @Test
    void describeReadShouldReturnLengthAndEtagWithoutPublicUrl() {
        when(ebookS3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(100L).eTag("etag").build());
        var metadata = service.describeRead("library-private", "ebooks/10/200/original.pdf");
        assertThat(metadata.contentLength()).isEqualTo(100);
        assertThat(metadata.etag()).isEqualTo("etag");
    }

    @Test
    void describeReadMustSanitizeUpstreamFailure() {
        when(ebookS3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(new IllegalStateException("private endpoint and credentials must never escape"));
        assertThatThrownBy(() -> service.describeRead("library-private", "ebooks/10/200/original.pdf"))
                .isInstanceOf(AppException.class).hasMessageNotContaining("credentials");
    }

    @Test
    void describeReadMustRejectMissingMetadata() {
        when(ebookS3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertThatThrownBy(() -> service.describeRead("library-private", "ebooks/10/200/original.pdf"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void openReadMustPassBoundedRangeAndEtagWithoutBuffering() throws Exception {
        var stream = new ResponseInputStream<>(GetObjectResponse.builder().contentLength(5L).build(),
                new ByteArrayInputStream("%PDF-".getBytes()));
        when(ebookS3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
        try (var result = service.openRead("library-private", "ebooks/10/200/original.pdf", "bytes=0-4", "etag", 5)) {
            assertThat(result).isSameAs(stream);
        }
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(ebookS3Client).getObject(request.capture());
        assertThat(request.getValue().range()).isEqualTo("bytes=0-4");
        assertThat(request.getValue().ifMatch()).isEqualTo("etag");
    }

    @Test
    void openReadMustRejectInconsistentLength() {
        var stream = new ResponseInputStream<>(GetObjectResponse.builder().contentLength(100L).build(),
                new ByteArrayInputStream("%PDF-".getBytes()));
        when(ebookS3Client.getObject(any(GetObjectRequest.class))).thenReturn(stream);
        assertThatThrownBy(() -> service.openRead("library-private", "ebooks/10/200/original.pdf", "bytes=0-4", "etag", 5))
                .isInstanceOf(AppException.class);
    }

    @Test
    void openReadMustSanitizeUpstreamFailure() {
        when(ebookS3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(new IllegalStateException("secret storage URL"));
        assertThatThrownBy(() -> service.openRead("library-private", "ebooks/10/200/original.pdf", null, "etag", 5))
                .isInstanceOf(AppException.class).hasMessageNotContaining("secret");
    }

    @Test
    void upload_shouldStageMultipartOnceAndProvideRepeatableS3Body() throws Exception {
        byte[] content = "ebook-pdf-content".getBytes();
        AtomicInteger openCount = new AtomicInteger();
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getOriginalFilename()).thenReturn("clean-code.pdf");
        when(file.getInputStream()).thenAnswer(invocation -> {
            openCount.incrementAndGet();
            return new ByteArrayInputStream(content);
        });
        when(ebookS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try (InputStream input = body.contentStreamProvider().newStream()) {
                            assertThat(input.readAllBytes()).isEqualTo(content);
                        }
                    }
                    return PutObjectResponse.builder().build();
                });
        when(ebookS3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) content.length).build());

        EbookObjectMetadata result = service.upload("ebooks/10/200/original.pdf", file);

        assertThat(openCount).hasValue(1);
        assertThat(result.bucket()).isEqualTo("library-private");
        assertThat(result.objectKey()).isEqualTo("ebooks/10/200/original.pdf");
        assertThat(result.checksumSha256()).isEqualTo(sha256(content));

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(ebookS3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.metadata()).containsEntry("original-filename", "clean-code.pdf");
        assertThat(request.metadata()).containsEntry("book-id", "10");
        assertThat(request.metadata()).containsEntry("ebook-id", "200");
        assertThat(request.metadata()).doesNotContainKey("sha256");
    }

    @Test
    void upload_shouldFailWhenStoredObjectSizeDoesNotMatchSource() throws Exception {
        byte[] content = "ebook-pdf-content".getBytes();
        when(file.getContentType()).thenReturn("application/pdf");
        when(file.getSize()).thenReturn((long) content.length);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(content));
        when(ebookS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    RequestBody body = invocation.getArgument(1);
                    try (InputStream input = body.contentStreamProvider().newStream()) {
                        input.readAllBytes();
                    }
                    return PutObjectResponse.builder().build();
                });
        when(ebookS3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long) content.length + 1).build());

        assertThatThrownBy(() -> service.upload("ebooks/10/200/original.pdf", file))
                .isInstanceOf(AppException.class);

        verify(ebookS3Client).headObject(any(HeadObjectRequest.class));
    }

    private String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    @Test
    void uploadStagingDirectory_shouldBePrivateOnPosixSystems() throws Exception {
        Path directory = S3EbookObjectStorageService.createStagingDirectory();
        try {
            assertThat(directory).isDirectory();
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertThat(Files.getPosixFilePermissions(directory))
                        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            }
        } finally {
            Files.delete(directory);
        }
    }
}
