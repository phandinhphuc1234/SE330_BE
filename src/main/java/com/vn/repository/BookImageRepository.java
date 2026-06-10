package com.vn.repository;

import com.vn.entity.BookImage;
import com.vn.enums.BookImageStatus;
import com.vn.enums.ImageProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookImageRepository extends JpaRepository<BookImage, Long> {

    // Lấy toàn bộ ảnh của một sách theo thứ tự hiển thị ổn định cho gallery/detail.
    List<BookImage> findByBookIdOrderBySortOrderAscIdAsc(Long bookId);

    // Ảnh primary đang active có thể được update lại khi staff thay cover của sách.
    Optional<BookImage> findFirstByBookIdAndPrimaryImageTrueAndStatusOrderBySortOrderAscIdAsc(
            Long bookId,
            BookImageStatus status
    );

    // Chặn một Cloudinary asset bị gán làm ảnh chính cho nhiều sách khác nhau.
    Optional<BookImage> findByProviderAndPublicId(ImageProvider provider, String publicId);

    // Batch load ảnh primary cho trang search để tránh N+1 query khi map BookSummaryResponse.
    @Query("""
            select image
            from BookImage image
            join fetch image.book book
            where book.id in :bookIds
              and image.primaryImage = true
              and image.status = com.vn.enums.BookImageStatus.ACTIVE
            """)
    List<BookImage> findPrimaryImagesByBookIds(@Param("bookIds") Collection<Long> bookIds);

    // Cleanup job dùng query có Pageable để giới hạn số asset Cloudinary retry mỗi lần chạy.
    List<BookImage> findByStatusOrderByUpdatedAtAsc(BookImageStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update BookImage image
            set image.status = :status,
                image.deletedAt = CURRENT_TIMESTAMP,
                image.updatedAt = CURRENT_TIMESTAMP
            where image.id = :imageId
            """)
    int updateStatusWithDeletedAt(@Param("imageId") Long imageId,
                                  @Param("status") BookImageStatus status);
}
