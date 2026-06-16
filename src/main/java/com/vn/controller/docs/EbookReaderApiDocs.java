package com.vn.controller.docs;

import com.vn.dto.common.ApiResponse;
import com.vn.dto.ebook.response.EbookReadingSessionCloseResponse;
import com.vn.dto.ebook.response.EbookReadingSessionRefreshResponse;
import com.vn.dto.ebook.response.EbookReadingSessionResponse;
import com.vn.dto.ebook.response.EbookSignedContentResponse;
import com.vn.security.MemberUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@Tag(name = "Ebook Reader", description = "APIs for secure ebook reading sessions and signed content URLs")
public interface EbookReaderApiDocs {

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Create ebook reading session",
            description = """
                    Create a short-lived reader session for a book ebook.
                    The authenticated member must already have an ACTIVE ebook loan.
                    The raw session token is returned once and must be sent in X-Reading-Session later.
                    """
    )
    ResponseEntity<ApiResponse<EbookReadingSessionResponse>> createReadingSession(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(hidden = true) MemberUserDetails userDetails,
            @Parameter(hidden = true) HttpServletRequest request
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Get signed ebook content URL",
            description = """
                    Return a short-lived Cloudinary signed URL after checking JWT, reader session and ebook loan.
                    The session token must be sent in X-Reading-Session, not in query string.
                    """
    )
    ResponseEntity<ApiResponse<EbookSignedContentResponse>> getSignedContent(
            @Parameter(description = "Book ID", required = true) Long bookId,
            @Parameter(description = "Raw reading session token", required = true) String rawSessionToken,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Refresh ebook reading session",
            description = "Extend the reader session up to the loan expiration time and refresh the Redis TTL."
    )
    ResponseEntity<ApiResponse<EbookReadingSessionRefreshResponse>> refreshReadingSession(
            @Parameter(description = "Reading session ID", required = true) Long sessionId,
            @Parameter(description = "Raw reading session token", required = true) String rawSessionToken,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );

    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Close ebook reading session",
            description = "Close an active reader session and remove its Redis cache entry."
    )
    ResponseEntity<ApiResponse<EbookReadingSessionCloseResponse>> closeReadingSession(
            @Parameter(description = "Reading session ID", required = true) Long sessionId,
            @Parameter(description = "Raw reading session token", required = true) String rawSessionToken,
            @Parameter(hidden = true) MemberUserDetails userDetails
    );
}
