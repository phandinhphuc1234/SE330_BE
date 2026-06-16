package com.vn.entity;

import com.vn.enums.PaymentEventProcessingStatus;
import com.vn.enums.PaymentEventType;
import com.vn.enums.PaymentProvider;
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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "payment_events")
@Getter
@Setter
@NoArgsConstructor
public class PaymentEvent {

    // Audit raw IPN/return từ provider. Không dùng bảng này làm source of truth payment status.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private PaymentEventType eventType;

    @Column(name = "provider_order_id", length = 100)
    private String providerOrderId;

    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    // Lưu nguyên payload để debug retry/sai chữ ký/đối soát mà không mất dữ liệu gốc.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawPayload = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_headers", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawHeaders = new LinkedHashMap<>();

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private PaymentEventProcessingStatus processingStatus;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
        if (this.processingStatus == null) {
            this.processingStatus = PaymentEventProcessingStatus.RECEIVED;
        }
        if (this.rawPayload == null) {
            this.rawPayload = new LinkedHashMap<>();
        }
        if (this.rawHeaders == null) {
            this.rawHeaders = new LinkedHashMap<>();
        }
    }
}
