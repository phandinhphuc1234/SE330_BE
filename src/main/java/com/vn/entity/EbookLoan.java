package com.vn.entity;

import com.vn.enums.EbookLoanStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Lưu lịch sử mượn ebook của member.
 * Một member có thể mượn cùng lúc tối đa MAX_CONCURRENT_EBOOK_LOANS quyển ebook.
 * Mỗi lượt mượn có thời hạn (expiresAt), hết hạn thì status chuyển EXPIRED.
 */
@Entity
@Table(name = "ebook_loans",
        indexes = {
                @Index(name = "idx_ebook_loans_member", columnList = "member_id"),
                @Index(name = "idx_ebook_loans_book", columnList = "book_id"),
                @Index(name = "idx_ebook_loans_status", columnList = "status"),
                @Index(name = "idx_ebook_loans_expires", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EbookLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /**
     * Book phải có ebookUrl != null mới cho mượn.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EbookLoanStatus status;

    /**
     * Thời điểm bắt đầu mượn.
     */
    @Column(nullable = false)
    private Instant borrowedAt;

    /**
     * Thời điểm ebook loan hết hạn (mặc định 14 ngày).
     */
    @Column(nullable = false)
    private Instant expiresAt;

    /**
     * Số lần đã gia hạn.
     */
    @Column(nullable = false)
    private Integer renewCount;

    /**
     * Số lần gia hạn tối đa được phép.
     */
    @Column(nullable = false)
    private Integer maxRenewals;

    /**
     * Thời điểm member trả sớm (tự bấm "Trả sách") — nullable.
     */
    private Instant returnedAt;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.borrowedAt == null) this.borrowedAt = now;
        if (this.status == null) this.status = EbookLoanStatus.ACTIVE;
        if (this.renewCount == null) this.renewCount = 0;
        if (this.maxRenewals == null) this.maxRenewals = 1;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
