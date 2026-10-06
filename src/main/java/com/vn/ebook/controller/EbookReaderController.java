package com.vn.ebook.controller;

import com.vn.ebook.controller.docs.EbookReaderApiDocs;
import com.vn.ebook.dto.request.EbookAskRequest;
import com.vn.ebook.dto.request.EbookSemanticSearchRequest;
import com.vn.ebook.dto.response.EbookAnswerResponse;
import com.vn.ebook.dto.response.EbookReadingSessionCloseResponse;
import com.vn.ebook.dto.response.EbookReadingSessionRefreshResponse;
import com.vn.ebook.dto.response.EbookReadingSessionResponse;
import com.vn.ebook.dto.response.EbookSemanticSearchResponse;
import com.vn.ebook.dto.response.EbookSignedContentResponse;
import com.vn.ebook.service.EbookQuestionAnswerService;
import com.vn.ebook.service.EbookReaderSessionService;
import com.vn.ebook.service.EbookSemanticSearchService;
import com.vn.shared.dto.ApiResponse;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.auth.security.MemberUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ebooks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MEMBER')")
public class EbookReaderController implements EbookReaderApiDocs {

    private static final String X_READING_SESSION = "X-Reading-Session";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String USER_AGENT = "User-Agent";

    private final EbookReaderSessionService ebookReaderSessionService;
    private final EbookSemanticSearchService ebookSemanticSearchService;
    private final EbookQuestionAnswerService ebookQuestionAnswerService;

    // Tạo phiên đọc sau khi user đã có ebook_loan ACTIVE; raw token chỉ trả về response này.
    @PostMapping("/{bookId}/reading-sessions")
    @Override
    public ResponseEntity<ApiResponse<EbookReadingSessionResponse>> createReadingSession(
            @PathVariable Long bookId,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            HttpServletRequest request) {
        EbookReadingSessionResponse response = ebookReaderSessionService.createSession(
                getCurrentMemberId(userDetails),
                bookId,
                resolveClientIp(request),
                request.getHeader(USER_AGENT)
        );
        return ResponseEntity.ok(ApiResponse.success("Tạo phiên đọc ebook thành công", response));
    }

    // Frontend phải gửi token qua header để tránh token lộ trong URL/log/history.
    @GetMapping("/{bookId}/reader/content")
    @Override
    public ResponseEntity<ApiResponse<EbookSignedContentResponse>> getSignedContent(
            @PathVariable Long bookId,
            @RequestHeader(value = X_READING_SESSION, required = false) String rawSessionToken,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        EbookSignedContentResponse response = ebookReaderSessionService.getSignedContent(
                getCurrentMemberId(userDetails),
                bookId,
                requireReadingSession(rawSessionToken)
        );
        return ResponseEntity.ok(ApiResponse.success("Lấy URL đọc ebook thành công", response));
    }

    @PostMapping("/{bookId}/reader/semantic-search")
    @Override
    public ResponseEntity<ApiResponse<EbookSemanticSearchResponse>> semanticSearch(
            @PathVariable Long bookId,
            @RequestHeader(value = X_READING_SESSION, required = false) String rawSessionToken,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody EbookSemanticSearchRequest request) {
        EbookSemanticSearchResponse response = ebookSemanticSearchService.search(
                getCurrentMemberId(userDetails),
                bookId,
                requireReadingSession(rawSessionToken),
                request
        );
        return ResponseEntity.ok(ApiResponse.success("Tìm kiếm nội dung ebook thành công", response));
    }

    @PostMapping("/{bookId}/reader/ask")
    @Override
    public ResponseEntity<ApiResponse<EbookAnswerResponse>> askThisBook(
            @PathVariable Long bookId,
            @RequestHeader(value = X_READING_SESSION, required = false) String rawSessionToken,
            @AuthenticationPrincipal MemberUserDetails userDetails,
            @Valid @RequestBody EbookAskRequest request) {
        EbookAnswerResponse response = ebookQuestionAnswerService.answer(
                getCurrentMemberId(userDetails),
                bookId,
                requireReadingSession(rawSessionToken),
                request
        );
        return ResponseEntity.ok(ApiResponse.success("Trả lời câu hỏi từ nội dung ebook thành công", response));
    }

    // Heartbeat định kỳ để kéo dài session, nhưng không vượt quá thời hạn loan.
    @PostMapping("/reading-sessions/{sessionId}/refresh")
    @Override
    public ResponseEntity<ApiResponse<EbookReadingSessionRefreshResponse>> refreshReadingSession(
            @PathVariable Long sessionId,
            @RequestHeader(value = X_READING_SESSION, required = false) String rawSessionToken,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        EbookReadingSessionRefreshResponse response = ebookReaderSessionService.refreshSession(
                getCurrentMemberId(userDetails),
                sessionId,
                requireReadingSession(rawSessionToken)
        );
        return ResponseEntity.ok(ApiResponse.success("Gia hạn phiên đọc ebook thành công", response));
    }

    // Đóng reader chủ động để backend thu hồi session cache sớm.
    @PostMapping("/reading-sessions/{sessionId}/close")
    @Override
    public ResponseEntity<ApiResponse<EbookReadingSessionCloseResponse>> closeReadingSession(
            @PathVariable Long sessionId,
            @RequestHeader(value = X_READING_SESSION, required = false) String rawSessionToken,
            @AuthenticationPrincipal MemberUserDetails userDetails) {
        EbookReadingSessionCloseResponse response = ebookReaderSessionService.closeSession(
                getCurrentMemberId(userDetails),
                sessionId,
                requireReadingSession(rawSessionToken)
        );
        return ResponseEntity.ok(ApiResponse.success("Đóng phiên đọc ebook thành công", response));
    }

    private Long getCurrentMemberId(MemberUserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return userDetails.getMember().getId();
    }

    private String requireReadingSession(String rawSessionToken) {
        if (!StringUtils.hasText(rawSessionToken)) {
            throw new AppException(ErrorCode.READING_SESSION_REQUIRED);
        }
        return rawSessionToken;
    }

    // Khi qua proxy/ngrok, X-Forwarded-For giữ IP thật hơn remoteAddr của reverse proxy.
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
