package com.vn.entity;

import com.vn.enums.BorrowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "borrow_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BorrowRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_copy_id", nullable = false)
    private BookCopy bookCopy;

    private Instant borrowedAt;

    private Instant dueDate;

    private Instant returnedAt;

    @Column(nullable = false)
    private BigDecimal fineAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fine_config_id")
    private FineConfig fineConfig;

    private Instant fineCalculatedAt;

    private Instant finePaidAt;

    private Long fineWaivedBy;

    @Column(length = 255)
    private String fineWaivedReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BorrowStatus status;

    @Column(nullable = false)
    private Integer renewCount;

    @Column(nullable = false)
    private Integer maxRenewalsAtCheckout;

    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.borrowedAt == null) {
            this.borrowedAt = now;
        }
        if (this.fineAmount == null) {
            this.fineAmount = BigDecimal.ZERO;
        }
        if (this.status == null) {
            this.status = BorrowStatus.BORROWED;
        }
        if (this.renewCount == null) {
            this.renewCount = 0;
        }
        if (this.maxRenewalsAtCheckout == null) {
            this.maxRenewalsAtCheckout = 1;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
