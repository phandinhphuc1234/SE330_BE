package com.vn.ebook.controller;

import com.vn.auth.security.MemberUserDetails;
import com.vn.ebook.service.EbookPdfContent;
import com.vn.ebook.service.EbookRangeNotSatisfiableException;
import com.vn.ebook.service.impl.EbookPdfContentService;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('MEMBER')")
public class EbookPdfController {
    private final EbookPdfContentService contentService;

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Read private PDF bytes", description = "JWT and X-Reading-Session are required on every GET/HEAD; single byte ranges are supported. Tokens never appear in URLs.")
    @RequestMapping(value = "/api/ebooks/{bookId}/reader/file", method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<Resource> readPdf(
            @PathVariable Long bookId,
            @RequestHeader(value = "X-Reading-Session", required = false) String sessionToken,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange,
            @AuthenticationPrincipal MemberUserDetails principal,
            HttpServletRequest request) {
        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        if (!StringUtils.hasText(sessionToken)) {
            throw new AppException(ErrorCode.READING_SESSION_REQUIRED);
        }
        // No public validator is issued for this no-store response. Conservatively
        // serve the full representation when If-Range cannot be validated.
        String requestedRange = ifRange == null ? range : null;
        EbookPdfContent content = contentService.read(principal.getMember().getId(), bookId,
                sessionToken, requestedRange, "HEAD".equals(request.getMethod()));
        HttpHeaders headers = privateHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(content.contentLength());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"book-" + bookId + ".pdf\"");
        if (content.partial()) {
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes %d-%d/%d".formatted(content.start(), content.end(), content.totalLength()));
        }
        return new ResponseEntity<>(content.resource(), headers,
                content.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    @ExceptionHandler(EbookRangeNotSatisfiableException.class)
    public ResponseEntity<Void> invalidRange(EbookRangeNotSatisfiableException exception) {
        HttpHeaders headers = privateHeaders();
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.getContentLength());
        return new ResponseEntity<>(headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    private HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        return headers;
    }
}
