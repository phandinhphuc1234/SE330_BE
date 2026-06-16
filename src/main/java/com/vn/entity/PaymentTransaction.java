package com.vn.entity;

import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.enums.PaymentStatus;
import com.vn.enums.PaymentTargetType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentTransaction {

    // Source of truth cho trạng thái một giao dịch thanh toán.
    // IPN/callback server-to-server ở spec sau mới được chuyển PENDING -> SUCCESS/FAILED.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_code", nullable = false, unique = true, length = 64)
    private String paymentCode;

    // Lưu ID trực tiếp để payment không phụ thuộc lazy-loading Member khi chỉ cần định danh user.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;

    @Column(name = "provider_order_id", length = 100)
    private String providerOrderId;

    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    // MVP chỉ có EBOOK_PAYMENT/BOOK_EBOOK, nhưng tách enum để mở rộng payment purpose sau này.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private PaymentTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false)
    private Long amount;

    // Amount do backend tính từ nghiệp vụ, frontend không được gửi amount.
    @Column(nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    // Dùng để audit request tạo payment đã đi qua idempotency key nào.
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "provider_response_code", length = 50)
    private String providerResponseCode;

    @Column(name = "provider_transaction_status", length = 50)
    private String providerTransactionStatus;

    // Metadata provider-specific như vnpCreateDate/vnpExpireDate phục vụ đối soát QueryDr sau này.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> providerMetadata = new LinkedHashMap<>();

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
            this.status = PaymentStatus.PENDING;
        }
        if (this.currency == null || this.currency.isBlank()) {
            this.currency = "VND";
        }
        if (this.providerMetadata == null) {
            this.providerMetadata = new LinkedHashMap<>();
        }
    }
}
