package com.vn.entity;

import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookAccessType;
import com.vn.enums.MediaProvider;
import com.vn.service.storage.MediaDeliveryType;
import com.vn.service.storage.MediaResourceType;
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
@Table(name = "book_ebooks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookEbook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MediaProvider provider;

    // Cloudinary publicId for the protected raw PDF, for example pdf/9780132350884/main.pdf.
    @Column(name = "public_id", nullable = false, length = 500)
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 30)
    private MediaResourceType resourceType;

    // Ebook PDF dùng AUTHENTICATED để frontend không dùng được URL public cố định.
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false, length = 30)
    private MediaDeliveryType deliveryType;

    @Column(length = 30)
    private String format;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    private Long version;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 128)
    private String checksum;

    // ACTIVE là bản ebook đang dùng; upload lại main.pdf sẽ giữ một row và cập nhật metadata.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookEbookStatus status;

    @Column(name = "max_concurrent_loans", nullable = false)
    private Integer maxConcurrentLoans;

    @Column(name = "loan_duration_days", nullable = false)
    private Integer loanDurationDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 30)
    private EbookAccessType accessType;

    @Column(name = "access_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal accessFee;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "access_duration_days", nullable = false)
    private Integer accessDurationDays;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // Default đặt ở entity để service không lặp lại các giá trị cấu hình cơ bản.
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
        if (this.provider == null) {
            this.provider = MediaProvider.CLOUDINARY;
        }
        if (this.resourceType == null) {
            this.resourceType = MediaResourceType.RAW;
        }
        if (this.deliveryType == null) {
            this.deliveryType = MediaDeliveryType.AUTHENTICATED;
        }
        if (this.status == null) {
            this.status = BookEbookStatus.ACTIVE;
        }
        if (this.maxConcurrentLoans == null) {
            this.maxConcurrentLoans = 5;
        }
        if (this.loanDurationDays == null) {
            this.loanDurationDays = 14;
        }
        if (this.accessType == null) {
            this.accessType = EbookAccessType.FREE;
        }
        if (this.accessFee == null) {
            this.accessFee = BigDecimal.ZERO;
        }
        if (this.currency == null || this.currency.isBlank()) {
            this.currency = "VND";
        }
        if (this.accessDurationDays == null) {
            this.accessDurationDays = this.loanDurationDays;
        }
    }
}
