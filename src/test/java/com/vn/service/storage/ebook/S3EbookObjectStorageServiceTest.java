package com.vn.service.storage.ebook;

import com.vn.config.ObjectStorageProperties;
import com.vn.exception.AppException;
import com.vn.service.storage.ebook.EbookObjectStorageService.EbookObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
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
    void upload_shouldStreamFileOnceAndCalculateChecksumDuringUpload() throws Exception {
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
                    body.contentStreamProvider().newStream().readAllBytes();
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
                    body.contentStreamProvider().newStream().readAllBytes();
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
}
