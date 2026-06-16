package com.vn.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.dto.ebook.response.EbookReadingSessionCloseResponse;
import com.vn.dto.ebook.response.EbookReadingSessionRefreshResponse;
import com.vn.dto.ebook.response.EbookReadingSessionResponse;
import com.vn.dto.ebook.response.EbookSignedContentResponse;
import com.vn.entity.BookEbook;
import com.vn.entity.EbookLoan;
import com.vn.entity.EbookReadingSession;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookLoanStatus;
import com.vn.enums.EbookReadingSessionStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.EbookLoanRepository;
import com.vn.repository.EbookReadingSessionRepository;
import com.vn.service.EbookReaderSessionService;
import com.vn.service.ebook.EbookReadingSessionCachePayload;
import com.vn.service.ebook.EbookReadingSessionTokenService;
import com.vn.service.storage.MediaSignedUrlCommand;
import com.vn.service.storage.MediaSignedUrlResult;
import com.vn.service.storage.MediaStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookReaderSessionServiceImpl implements EbookReaderSessionService {

    // Redis key lưu theo token hash, không theo raw token để tránh lộ secret nếu inspect Redis.
    private static final String CACHE_KEY_PREFIX = "reading_session:";
    // Mỗi session đọc sống ngắn; frontend phải refresh định kỳ nếu user còn mở reader.
    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    // Signed URL chỉ nên sống ngắn hơn session để giảm rủi ro bị copy URL ra ngoài.
    private static final Duration SIGNED_URL_TTL = Duration.ofMinutes(5);
    // Không update lastHeartbeatAt mỗi request refresh để tránh ghi DB quá dày.
    private static final Duration HEARTBEAT_THROTTLE = Duration.ofMinutes(5);
    private static final int MAX_IP_LENGTH = 100;

    private final BookEbookRepository bookEbookRepository;
    private final EbookLoanRepository ebookLoanRepository;
    private final EbookReadingSessionRepository readingSessionRepository;
    private final EbookReadingSessionTokenService tokenService;
    private final MediaStorageService mediaStorageService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    @Transactional
    public EbookReadingSessionResponse createSession(Long memberId, Long bookId, String ipAddress, String userAgent) {
        Instant now = Instant.now();
        // Chỉ ebook ACTIVE mới được mở reader; ebook bị inactive/deleted thì không tạo session mới.
        BookEbook ebook = findActiveEbookForBook(bookId);
        // Loan là quyền đọc thật. Không có loan ACTIVE thì không được tạo vé đọc ngắn hạn.
        EbookLoan loan = findCurrentLoan(memberId, bookId, ebook.getId(), now);

        // Raw token trả cho frontend một lần; DB/Redis chỉ lưu hash để giảm thiệt hại nếu DB lộ.
        String rawToken = tokenService.generateRawToken();
        String tokenHash = tokenService.hashToken(rawToken);
        // Session không bao giờ được sống lâu hơn loan.
        Instant sessionExpiresAt = min(now.plus(SESSION_TTL), loan.getExpiredAt());

        // PostgreSQL là source of truth để worker/revoke/audit về sau có dữ liệu bền vững.
        EbookReadingSession session = new EbookReadingSession();
        session.setSessionTokenHash(tokenHash);
        session.setMemberId(memberId);
        session.setBookId(bookId);
        session.setBookEbookId(ebook.getId());
        session.setLoanId(loan.getId());
        session.setStatus(EbookReadingSessionStatus.ACTIVE);
        session.setSessionExpiresAt(sessionExpiresAt);
        session.setLastHeartbeatAt(now);
        session.setIpAddress(limit(ipAddress, MAX_IP_LENGTH));
        session.setUserAgentHash(tokenService.hashUserAgent(userAgent));
        EbookReadingSession savedSession = readingSessionRepository.save(session);

        // Redis giúp lần xin signed URL sau đó không phải query session table nếu session còn sống.
        cacheSession(savedSession, loan, now);
        return new EbookReadingSessionResponse(
                savedSession.getId(),
                rawToken,
                savedSession.getBookId(),
                savedSession.getBookEbookId(),
                savedSession.getLoanId(),
                loan.getExpiredAt(),
                savedSession.getSessionExpiresAt(),
                now
        );
    }

    @Override
    @Transactional(readOnly = true)
    public EbookSignedContentResponse getSignedContent(Long memberId, Long bookId, String rawSessionToken) {
        Instant now = Instant.now();
        // API chỉ nhận raw token qua header X-Reading-Session; service chuyển sang hash để lookup.
        String tokenHash = tokenService.hashToken(rawSessionToken);
        // Resolve session từ Redis trước, miss thì fallback DB; cả hai đường đều check owner/status/loan.
        ActiveSessionContext context = resolveActiveSession(memberId, bookId, tokenHash, now);
        BookEbook ebook = findActiveEbookForSession(context);

        // URL đọc PDF không được sống lâu hơn session hoặc loan còn lại.
        Instant expiresAt = min(now.plus(SIGNED_URL_TTL), context.sessionExpiresAt(), context.loan().getExpiredAt());
        if (!expiresAt.isAfter(now)) {
            throw new AppException(ErrorCode.READING_SESSION_NOT_ACTIVE);
        }

        // Business service chỉ gọi storage abstraction; chi tiết ký URL nằm ở CloudinaryStorageService.
        MediaSignedUrlResult signedUrl = mediaStorageService.generateSignedUrl(new MediaSignedUrlCommand(
                ebook.getPublicId(),
                ebook.getResourceType(),
                ebook.getDeliveryType(),
                ebook.getFormat(),
                expiresAt
        ));
        return new EbookSignedContentResponse(signedUrl.signedUrl(), signedUrl.expiresAt(), now);
    }

    @Override
    @Transactional
    public EbookReadingSessionRefreshResponse refreshSession(Long memberId, Long sessionId, String rawSessionToken) {
        Instant now = Instant.now();
        String tokenHash = tokenService.hashToken(rawSessionToken);
        // Refresh/close dùng query có lock để không đua với request khác hoặc worker expire/revoke.
        EbookReadingSession session = readingSessionRepository.findByIdAndSessionTokenHash(sessionId, tokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.READING_SESSION_NOT_FOUND));
        validateSessionOwnerAndState(session, memberId, session.getBookId(), now);

        // Mỗi lần refresh vẫn check loan realtime; Redis session còn sống không có nghĩa loan còn hợp lệ.
        EbookLoan loan = loadActiveLoan(session, now);
        Instant refreshedExpiresAt = min(now.plus(SESSION_TTL), loan.getExpiredAt());
        session.setSessionExpiresAt(refreshedExpiresAt);
        if (session.getLastHeartbeatAt() == null
                || session.getLastHeartbeatAt().isBefore(now.minus(HEARTBEAT_THROTTLE))) {
            session.setLastHeartbeatAt(now);
        }

        EbookReadingSession savedSession = readingSessionRepository.save(session);
        // Cập nhật lại TTL Redis theo thời hạn mới, vẫn bị chặn bởi loan.expiredAt.
        cacheSession(savedSession, loan, now);
        return new EbookReadingSessionRefreshResponse(
                savedSession.getId(),
                savedSession.getStatus().name(),
                loan.getExpiredAt(),
                savedSession.getSessionExpiresAt(),
                now
        );
    }

    @Override
    @Transactional
    public EbookReadingSessionCloseResponse closeSession(Long memberId, Long sessionId, String rawSessionToken) {
        Instant now = Instant.now();
        String tokenHash = tokenService.hashToken(rawSessionToken);
        // Token hash + sessionId giúp đóng đúng session, không đóng nhầm session khác của user.
        EbookReadingSession session = readingSessionRepository.findByIdAndSessionTokenHash(sessionId, tokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.READING_SESSION_NOT_FOUND));
        if (!Objects.equals(session.getMemberId(), memberId)) {
            throw new AppException(ErrorCode.READING_SESSION_FORBIDDEN);
        }

        // Close idempotent nhẹ: nếu đã CLOSED/EXPIRED/REVOKED thì không chuyển ngược trạng thái.
        if (session.getStatus() == EbookReadingSessionStatus.ACTIVE) {
            session.setStatus(EbookReadingSessionStatus.CLOSED);
            session.setClosedAt(now);
            readingSessionRepository.save(session);
        }
        // Xóa cache để token này không xin signed URL được nữa sau khi đóng reader.
        deleteCache(tokenHash);
        return new EbookReadingSessionCloseResponse(
                session.getId(),
                session.getStatus().name(),
                session.getClosedAt(),
                now
        );
    }

    private BookEbook findActiveEbookForBook(Long bookId) {
        // Lấy ebook ACTIVE mới nhất của book; upload thay thế PDF vẫn giữ logic mở reader theo book.
        return bookEbookRepository.findFirstByBookIdAndStatusOrderByIdDesc(bookId, BookEbookStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));
    }

    private EbookLoan findCurrentLoan(Long memberId, Long bookId, Long bookEbookId, Instant now) {
        // Một member chỉ mở reader khi có loan ACTIVE còn hạn cho đúng book_ebook_id.
        return ebookLoanRepository
                .findFirstByMemberIdAndBookIdAndBookEbookIdAndStatusAndExpiredAtAfterOrderByExpiredAtDesc(
                        memberId,
                        bookId,
                        bookEbookId,
                        EbookLoanStatus.ACTIVE,
                        now
                )
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_LOAN_REQUIRED));
    }

    private ActiveSessionContext resolveActiveSession(Long memberId, Long bookId, String tokenHash, Instant now) {
        // Fast path: Redis hit đủ để xác định session metadata, sau đó vẫn phải load loan để check quyền thật.
        EbookReadingSessionCachePayload cached = readCachedSession(tokenHash);
        if (cached != null) {
            validateCachedPayload(cached, memberId, bookId, now, tokenHash);
            EbookLoan loan = loadActiveLoan(cached, now);
            return new ActiveSessionContext(
                    cached.sessionId(),
                    cached.memberId(),
                    cached.bookId(),
                    cached.bookEbookId(),
                    cached.loanId(),
                    cached.sessionExpiresAt(),
                    loan
            );
        }

        // Slow path: Redis miss thì dùng DB source of truth, sau đó nạp lại Redis nếu session còn hợp lệ.
        EbookReadingSession session = readingSessionRepository.findBySessionTokenHash(tokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.READING_SESSION_NOT_FOUND));
        validateSessionOwnerAndState(session, memberId, bookId, now);
        EbookLoan loan = loadActiveLoan(session, now);
        cacheSession(session, loan, now);
        return new ActiveSessionContext(
                session.getId(),
                session.getMemberId(),
                session.getBookId(),
                session.getBookEbookId(),
                session.getLoanId(),
                session.getSessionExpiresAt(),
                loan
        );
    }

    private void validateCachedPayload(EbookReadingSessionCachePayload payload,
                                       Long memberId,
                                       Long bookId,
                                       Instant now,
                                       String tokenHash) {
        // Cache hết hạn/trạng thái không ACTIVE thì xóa key để lần sau không dùng dữ liệu cũ.
        if (!EbookReadingSessionStatus.ACTIVE.name().equals(payload.status())
                || payload.sessionExpiresAt() == null
                || !payload.sessionExpiresAt().isAfter(now)) {
            deleteCache(tokenHash);
            throw new AppException(ErrorCode.READING_SESSION_NOT_ACTIVE);
        }
        // Token hợp lệ nhưng không thuộc JWT user hoặc book route hiện tại thì từ chối.
        if (!Objects.equals(payload.memberId(), memberId) || !Objects.equals(payload.bookId(), bookId)) {
            throw new AppException(ErrorCode.READING_SESSION_FORBIDDEN);
        }
    }

    private void validateSessionOwnerAndState(EbookReadingSession session, Long memberId, Long bookId, Instant now) {
        // Session luôn bind với member và book; user không thể dùng token của người khác/sách khác.
        if (!Objects.equals(session.getMemberId(), memberId) || !Objects.equals(session.getBookId(), bookId)) {
            throw new AppException(ErrorCode.READING_SESSION_FORBIDDEN);
        }
        // CLOSED/EXPIRED/REVOKED hoặc quá session_expires_at đều không được nạp lại Redis/cấp URL.
        if (session.getStatus() != EbookReadingSessionStatus.ACTIVE
                || session.getSessionExpiresAt() == null
                || !session.getSessionExpiresAt().isAfter(now)) {
            throw new AppException(ErrorCode.READING_SESSION_NOT_ACTIVE);
        }
    }

    private EbookLoan loadActiveLoan(EbookReadingSession session, Instant now) {
        // Dù session còn sống, vẫn phải đọc loan vì quyền đọc có thể bị expire/revoke sau khi cache tạo.
        EbookLoan loan = ebookLoanRepository.findById(session.getLoanId())
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_LOAN_REQUIRED));
        validateLoanBelongsToSession(loan, session.getMemberId(), session.getBookId(), session.getBookEbookId(), now);
        return loan;
    }

    private EbookLoan loadActiveLoan(EbookReadingSessionCachePayload payload, Instant now) {
        // Redis chỉ cache session metadata, không thay thế check loan từ PostgreSQL.
        EbookLoan loan = ebookLoanRepository.findById(payload.loanId())
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_LOAN_REQUIRED));
        validateLoanBelongsToSession(loan, payload.memberId(), payload.bookId(), payload.bookEbookId(), now);
        return loan;
    }

    private void validateLoanBelongsToSession(EbookLoan loan,
                                              Long memberId,
                                              Long bookId,
                                              Long bookEbookId,
                                              Instant now) {
        // Chống trường hợp session/loan bị lệch dữ liệu hoặc token hash trỏ nhầm loan.
        if (!Objects.equals(loan.getMemberId(), memberId)
                || !Objects.equals(loan.getBookId(), bookId)
                || !Objects.equals(loan.getBookEbookId(), bookEbookId)) {
            throw new AppException(ErrorCode.EBOOK_LOAN_REQUIRED);
        }
        // Loan hết hạn thì session không còn quyền cấp signed URL nữa.
        if (loan.getStatus() != EbookLoanStatus.ACTIVE || !loan.getExpiredAt().isAfter(now)) {
            throw new AppException(ErrorCode.EBOOK_LOAN_EXPIRED);
        }
    }

    private BookEbook findActiveEbookForSession(ActiveSessionContext context) {
        // Signed URL phải dùng publicId hiện tại của ebook ACTIVE, không dùng publicId do frontend truyền.
        BookEbook ebook = bookEbookRepository.findByIdAndBookId(context.bookEbookId(), context.bookId())
                .orElseThrow(() -> new AppException(ErrorCode.EBOOK_NOT_FOUND));
        if (ebook.getStatus() != BookEbookStatus.ACTIVE) {
            throw new AppException(ErrorCode.EBOOK_NOT_AVAILABLE);
        }
        return ebook;
    }

    private void cacheSession(EbookReadingSession session, EbookLoan loan, Instant now) {
        // Redis TTL là min(session TTL, loan TTL) để cache tự biến mất khi một trong hai hết hạn.
        Instant ttlUntil = min(session.getSessionExpiresAt(), loan.getExpiredAt());
        Duration ttl = Duration.between(now, ttlUntil);
        if (!ttl.isPositive()) {
            deleteCache(session.getSessionTokenHash());
            return;
        }

        EbookReadingSessionCachePayload payload = new EbookReadingSessionCachePayload(
                session.getId(),
                session.getMemberId(),
                session.getBookId(),
                session.getBookEbookId(),
                session.getLoanId(),
                session.getSessionExpiresAt(),
                loan.getExpiredAt(),
                session.getStatus().name()
        );
        try {
            // Cache write fail không làm fail request vì PostgreSQL vẫn là source of truth.
            redisTemplate.opsForValue().set(cacheKey(session.getSessionTokenHash()), writePayload(payload), ttl);
        } catch (RuntimeException e) {
            log.warn("Could not cache ebook reading session. sessionId={}", session.getId(), e);
        }
    }

    private EbookReadingSessionCachePayload readCachedSession(String tokenHash) {
        String json;
        try {
            // Redis miss là bình thường; fallback DB ở resolveActiveSession.
            json = redisTemplate.opsForValue().get(cacheKey(tokenHash));
        } catch (RuntimeException e) {
            log.warn("Could not read ebook reading session cache", e);
            return null;
        }
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, EbookReadingSessionCachePayload.class);
        } catch (JsonProcessingException e) {
            // Cache bị hỏng format thì xóa và fallback DB ở request kế tiếp.
            deleteCache(tokenHash);
            return null;
        }
    }

    private String writePayload(EbookReadingSessionCachePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private void deleteCache(String tokenHash) {
        try {
            redisTemplate.delete(cacheKey(tokenHash));
        } catch (RuntimeException e) {
            // Delete cache fail không được làm rollback close/expire DB state.
            log.warn("Could not delete ebook reading session cache", e);
        }
    }

    private String cacheKey(String tokenHash) {
        return CACHE_KEY_PREFIX + tokenHash;
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private Instant min(Instant first, Instant second, Instant third) {
        return min(min(first, second), third);
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private record ActiveSessionContext(
            Long sessionId,
            Long memberId,
            Long bookId,
            Long bookEbookId,
            Long loanId,
            Instant sessionExpiresAt,
            EbookLoan loan
    ) {
        // Context đã được validate, dùng để tránh truyền nhiều biến rời rạc giữa các bước cấp signed URL.
    }
}
