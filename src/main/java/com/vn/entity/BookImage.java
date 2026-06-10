package com.vn.entity;

import com.vn.enums.BookImageType;
import com.vn.enums.BookImageStatus;
import com.vn.enums.ImageProvider;
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

import java.time.Instant;

@Entity
@Table(name = "book_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    // Provider giúp hệ thống còn mở rộng được nếu sau này không chỉ dùng Cloudinary.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ImageProvider provider;

    // Cloudinary publicId dùng cho quản trị ảnh: nhận diện, thay thế hoặc xóa asset.
    @Column(nullable = false, length = 512)
    private String publicId;

    // URL HTTPS được trả về frontend để browser tải trực tiếp từ CDN.
    @Column(nullable = false, length = 2048)
    private String secureUrl;

    // Phân loại ảnh để sau này hỗ trợ bìa sau, preview hoặc các ảnh phụ khác.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BookImageType assetType;

    @Column(length = 20)
    private String format;

    private Integer width;

    private Integer height;

    private Long version;

    @Column(length = 100)
    private String mimeType;

    private Long sizeBytes;

    @Column(length = 255)
    private String altText;

    @Column(nullable = false)
    private Integer sortOrder;

    // Ảnh primary là ảnh bìa chính được expose qua BookSummaryResponse.coverImage.
    @Column(name = "is_primary", nullable = false)
    private Boolean primaryImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookImageStatus status;

    private Instant deletedAt;

    private Instant createdAt;

    private Instant updatedAt;

    // Gán default ở entity để code service không phải lặp lại các giá trị mặc định.
    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.provider == null) {
            this.provider = ImageProvider.CLOUDINARY;
        }
        if (this.assetType == null) {
            this.assetType = BookImageType.COVER_FRONT;
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
        if (this.primaryImage == null) {
            this.primaryImage = false;
        }
        if (this.status == null) {
            this.status = BookImageStatus.ACTIVE;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
