package com.vn.ebook.service;

import com.vn.ebook.dto.response.EbookReadingSessionCloseResponse;
import com.vn.ebook.dto.response.EbookReadingSessionRefreshResponse;
import com.vn.ebook.dto.response.EbookReadingSessionResponse;
import com.vn.ebook.dto.response.EbookSignedContentResponse;

// Contract nghiệp vụ cho web reader ebook.
// Controller chỉ nhận HTTP/JWT/header; service này quyết định quyền đọc và cấp signed URL.
public interface EbookReaderSessionService {

    // Tạo session đọc mới sau khi xác nhận member có ebook_loan ACTIVE cho book này.
    // raw session token chỉ được trả ra ở response này một lần.
    EbookReadingSessionResponse createSession(Long memberId, Long bookId, String ipAddress, String userAgent);

    // Cấp URL object storage ngắn hạn sau khi check JWT member, reading session và ebook loan.
    EbookSignedContentResponse getSignedContent(Long memberId, Long bookId, String rawSessionToken);

    // Xác thực lại session + loan ngay trước các tác vụ reader khác như semantic search.
    EbookReadingAccess authorizeAccess(Long memberId, Long bookId, String rawSessionToken);

    // Gia hạn session đọc ngắn hạn; thời hạn mới không được vượt quá loan.expiredAt.
    EbookReadingSessionRefreshResponse refreshSession(Long memberId, Long sessionId, String rawSessionToken);

    // Đóng session khi user rời reader; DB chuyển CLOSED và Redis cache bị xóa.
    EbookReadingSessionCloseResponse closeSession(Long memberId, Long sessionId, String rawSessionToken);
}
