package com.vn.entity;

import com.vn.enums.EbookLoanStatus;
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
@Table(name = "ebook_loans")
@Getter
@Setter
@NoArgsConstructor
public class EbookLoan {

    // Quyền đọc ebook đã cấp cho member. payment_id giúp IPN retry không tạo loan lần hai.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "book_ebook_id", nullable = false)
    private Long bookEbookId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EbookLoanStatus status;

    @Column(name = "borrowed_at", nullable = false)
    private Instant borrowedAt;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        applyDefaults();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
        applyDefaults();
    }

    private void applyDefaults() {
        if (this.status == null) {
            this.status = EbookLoanStatus.ACTIVE;
        }
    }
}
