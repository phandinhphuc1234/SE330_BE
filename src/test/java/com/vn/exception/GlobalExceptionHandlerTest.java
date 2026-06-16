package com.vn.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMaxUploadSizeExceeded_shouldReturnEbookValidationErrorForEbookUpload() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/books/3105/ebooks");

        var response = handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(1_048_576L), request);

        assertThat(response.getStatusCode().value()).isEqualTo(ErrorCode.INVALID_EBOOK_FILE.getStatus().value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INVALID_EBOOK_FILE.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.INVALID_EBOOK_FILE.getMessage());
    }

    @Test
    void handleMaxUploadSizeExceeded_shouldReturnGenericMediaErrorForOtherUpload() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/books/3105/cover");

        var response = handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(1_048_576L), request);

        assertThat(response.getStatusCode().value()).isEqualTo(ErrorCode.INVALID_MEDIA_FILE.getStatus().value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.INVALID_MEDIA_FILE.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.INVALID_MEDIA_FILE.getMessage());
    }
}
