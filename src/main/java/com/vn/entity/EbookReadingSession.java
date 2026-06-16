package com.vn.entity;

import com.vn.enums.EbookReadingSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ebook_reading_sessions")
@Getter
@Setter
@NoArgsConstructor
public class EbookReadingSession {

    // Entity này là source of truth của phiên đọc ebook.
    // Redis chỉ là cache tăng tốc; nếu Redis mất key thì backend fallback về row này.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Hash HMAC của raw token. Raw token chỉ trả về frontend một lần và không lưu vào DB.
    @Column(name = "session_token_hash", nullable = false, unique = true, length = 255)
    private String sessionTokenHash;

    // Member đang sở hữu phiên đọc; dùng để bind session với JWT hiện tại.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // Book id được giữ trực tiếp để check nhanh route /api/ebooks/{bookId}/reader/content.
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    // Ebook/PDF cụ thể đang đọc. Một book sau này có thể có nhiều resource nên không chỉ lưu bookId.
    @Column(name = "book_ebook_id", nullable = false)
    private Long bookEbookId;

    // Loan cấp quyền đọc dài hạn; session hợp lệ khi loan này còn ACTIVE và chưa hết hạn.
    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    // Trạng thái lifecycle của phiên đọc: ACTIVE, EXPIRED, CLOSED hoặc REVOKED.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EbookReadingSessionStatus status;

    // Thời điểm session ngắn hạn hết hạn; refresh sẽ kéo dài nhưng không vượt loan.expiredAt.
    @Column(name = "session_expires_at", nullable = false)
    private Instant sessionExpiresAt;

    // Heartbeat cuối cùng từ frontend; update có throttle để tránh ghi DB quá dày.
    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    // User đóng reader bình thường.
    @Column(name = "closed_at")
    private Instant closedAt;

    // Worker spec 06 sẽ set field này khi session quá hạn.
    @Column(name = "expired_at")
    private Instant expiredAt;

    // Admin/hệ thống thu hồi session trước hạn.
    @Column(name = "revoked_at")
    private Instant revokedAt;

    // Lý do revoke để frontend/admin/audit đọc dễ hơn.
    @Column(name = "revoke_reason", length = 100)
    private String revokeReason;

    // IP rút gọn phục vụ audit/debug, không dùng để authorize.
    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    // Hash user-agent để audit mà không lưu raw user-agent dài/nhạy cảm.
    @Column(name = "user_agent_hash", length = 128)
    private String userAgentHash;

    // Optimistic version để worker/request song song không ghi đè trạng thái nhau về sau.
    @Version
    @Column(nullable = false)
    private Long version;

    // Timestamp tạo row.
    @Column(nullable = false)
    private Instant createdAt;

    // Timestamp cập nhật row gần nhất.
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        // Entity tự set timestamp/default để service không phải lặp boilerplate khi tạo session.
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        applyDefaults();
    }

    @PreUpdate
    void preUpdate() {
        // Mọi thay đổi trạng thái/heartbeat đều cập nhật updatedAt để audit được lifecycle.
        this.updatedAt = Instant.now();
        applyDefaults();
    }

    private void applyDefaults() {
        if (this.status == null) {
            this.status = EbookReadingSessionStatus.ACTIVE;
        }
    }
}
