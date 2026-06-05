package com.vn.repository;

import com.vn.entity.BookImage;
import com.vn.enums.ImageProvider;
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

    // Ảnh primary có thể được update lại khi staff thay imageUrl của sách.
    Optional<BookImage> findFirstByBookIdAndPrimaryImageTrueOrderBySortOrderAscIdAsc(Long bookId);

    // Chặn một Cloudinary asset bị gán làm ảnh chính cho nhiều sách khác nhau.
    Optional<BookImage> findByProviderAndPublicId(ImageProvider provider, String publicId);

    // Batch load ảnh primary cho trang search để tránh N+1 query khi map BookSummaryResponse.
    @Query("""
            select image
            from BookImage image
            join fetch image.book book
            where book.id in :bookIds
              and image.primaryImage = true
            """)
    List<BookImage> findPrimaryImagesByBookIds(@Param("bookIds") Collection<Long> bookIds);

    // Detail chỉ cần URL ảnh chính, không cần load toàn bộ entity ảnh.
    @Query("""
            select image.secureUrl
            from BookImage image
            where image.book.id = :bookId
              and image.primaryImage = true
            order by image.sortOrder asc, image.id asc
            """)
    Optional<String> findPrimaryImageUrlByBookId(@Param("bookId") Long bookId);

    // Blank imageUrl trong update nghĩa là xóa ảnh bìa chính khỏi catalog.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from BookImage image
            where image.book.id = :bookId
              and image.primaryImage = true
            """)
    int deletePrimaryImagesByBookId(@Param("bookId") Long bookId);
}
